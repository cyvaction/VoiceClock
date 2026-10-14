package com.cyvaction.voiceclock

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
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

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "后台运行", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("正在读取报时计划"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        serviceScope.launch {
            SettingsStore.setResidentServiceFailed(this@ResidentService, false)
        }
        serviceScope.launch {
            SettingsStore.flow(this@ResidentService)
                .map { residentNotificationText(it.announcementsEnabled, it.plannedAtMillis) }
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
            .setContentText(text)
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

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
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
