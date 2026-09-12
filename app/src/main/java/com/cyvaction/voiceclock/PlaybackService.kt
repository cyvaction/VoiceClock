package com.cyvaction.voiceclock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

class PlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "语音报时", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("语音时钟")
            .setContentText("正在报时")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rawId = intent?.getIntExtra(EXTRA_RAW_ID, 0) ?: 0
        val plannedAt = intent?.getLongExtra(EXTRA_PLANNED_AT, 0) ?: 0
        if (rawId == 0 || plannedAt == 0L) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        scope.launch {
            withTimeoutOrNull(15_000) {
                VoicePlayer.play(this@PlaybackService, rawId) {
                    val settings = SettingsStore.get(this@PlaybackService)
                    val summerStart = ClockLogic.parseStart(settings.summerStart)
                    val winterStart = ClockLogic.parseStart(settings.winterStart)
                    val instant = Instant.ofEpochMilli(plannedAt)
                    settings.announcementsEnabled && summerStart != null && winterStart != null &&
                        ClockLogic.shouldPlay(instant, Instant.now()) &&
                        ClockLogic.isAnnouncementEnabled(
                            instant,
                            summerStart,
                            winterStart,
                            settings.summerMask,
                            settings.winterMask,
                        )
                }
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_RAW_ID = "raw_id"
        const val EXTRA_PLANNED_AT = "planned_at"
        private const val CHANNEL_ID = "announcement_playback"
        private const val NOTIFICATION_ID = 1001
    }
}
