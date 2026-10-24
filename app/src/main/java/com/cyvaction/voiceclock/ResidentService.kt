package com.cyvaction.voiceclock

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

class ResidentService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val checks = Channel<String>(Channel.CONFLATED)
    private var checkLoop: Job? = null
    private var batteryRegistered = false
    private var threshold = 25
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            BatteryReminder.cancelIfRecovered(context, threshold)
            checks.trySend("battery-event")
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "后台运行", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification("正在读取报时计划"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (error: RuntimeException) {
            Log.w("ResidentService", "Foreground promotion rejected", error)
            serviceScope.launch {
                try { SettingsStore.setResidentServiceFailed(this@ResidentService, true) }
                finally { stopSelf() }
            }
            return
        }
        current = this
        isReady = true
        serviceScope.launch {
            for (source in checks) {
                try {
                    BatteryReminder.check(this@ResidentService, source)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w("BatteryReminder", "Check failed; awaiting next opportunity", error)
                }
            }
        }
        serviceScope.launch {
            SettingsStore.flow(this@ResidentService)
                .map { Triple(it.batteryEnabled, it.batteryRevision, it.threshold) }
                .distinctUntilChanged()
                .collect { (enabled, _, newThreshold) ->
                    threshold = newThreshold
                    VoicePlayer.priority.cancelBattery()
                    if (enabled) {
                        startBatteryMonitoring()
                        checks.trySend("settings-or-service-start")
                    } else {
                        stopBatteryMonitoring()
                        BatteryWorker.cancel(this@ResidentService)
                    }
                }
        }
        serviceScope.launch {
            SettingsStore.setResidentServiceFailed(this@ResidentService, false)
        }
        serviceScope.launch {
            SettingsStore.flow(this@ResidentService)
                .map { residentNotificationText(it.announcementsEnabled, it.plannedAtMillis) + "\n" + batteryStatusText(it) }
                .distinctUntilChanged()
                .catch {
                    Log.w("ResidentService", "Unable to read announcement plan", it)
                    emit("报时计划读取失败，请打开应用重试")
                }
                .collect { updateNotification(it) }
        }
    }

    private fun notification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("语音时钟")
            .setContentText(text.substringBefore("\n"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    @SuppressLint("MissingPermission") // Foreground notification remains required even when permission is denied.
    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        }.onFailure {
            Log.w("ResidentService", "Unable to update foreground notification", it)
        }
    }

    private fun startBatteryMonitoring() {
        if (batteryRegistered) return
        ContextCompat.registerReceiver(this, batteryReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        batteryRegistered = true
        BatteryWorker.schedule(this)
        checkLoop = serviceScope.launch {
            while (isActive) {
                delay(60_000L) // No wake lock or alarm: deliberately pauses with CPU sleep.
                checks.trySend("resident-timer")
            }
        }
    }

    private fun stopBatteryMonitoring() {
        checkLoop?.cancel()
        checkLoop = null
        if (batteryRegistered) {
            unregisterReceiver(batteryReceiver)
            batteryRegistered = false
        }
    }

    override fun onDestroy() {
        isReady = false
        current = null
        VoicePlayer.priority.cancelBattery()
        stopBatteryMonitoring()
        checks.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile internal var isReady = false
            private set
        @Volatile private var current: ResidentService? = null

        fun requestBatteryCheck(source: String) {
            current?.checks?.trySend(source)
        }

        internal const val CHANNEL_ID = "resident_service"
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            val appContext = context.applicationContext
            runCatching {
                ContextCompat.startForegroundService(appContext, Intent(appContext, ResidentService::class.java))
            }.onFailure {
                Log.w("ResidentService", "Unable to start foreground service", it)
                CoroutineScope(Dispatchers.IO).launch {
                    SettingsStore.setResidentServiceFailed(appContext, true)
                }
            }
        }
    }
}

internal fun residentNotificationText(enabled: Boolean, plannedAtMillis: Long): String = when {
    !enabled -> "报时已暂停"
    plannedAtMillis <= 0 -> "暂无可用报时计划"
    else -> "下次报时 · " + DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)
        .withZone(ClockLogic.beijingZone)
        .format(Instant.ofEpochMilli(plannedAtMillis))
}
