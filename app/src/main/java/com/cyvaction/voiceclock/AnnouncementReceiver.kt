package com.cyvaction.voiceclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant

class AnnouncementReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val plannedAt = intent.getLongExtra(AnnouncementScheduler.EXTRA_PLANNED_AT, 0)
        if (plannedAt <= 0) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = SettingsStore.flow(context).first()
                if (settings.announcementsEnabled && settings.plannedAtMillis == plannedAt) {
                    AnnouncementScheduler.reschedule(context)
                    if (ClockLogic.shouldPlay(Instant.ofEpochMilli(plannedAt), Instant.now())) {
                        val service = Intent(context, PlaybackService::class.java)
                            .putExtra(PlaybackService.EXTRA_PLANNED_AT, plannedAt)
                            .putExtra(
                                PlaybackService.EXTRA_RAW_ID,
                                VoiceAssets.announcements[ClockLogic.announcementSlot(Instant.ofEpochMilli(plannedAt))],
                            )
                        ContextCompat.startForegroundService(context, service)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
