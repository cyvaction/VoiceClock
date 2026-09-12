package com.cyvaction.voiceclock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant

object AnnouncementScheduler {
    const val EXTRA_PLANNED_AT = "planned_at"

    fun canSchedule(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    suspend fun reschedule(context: Context, now: Instant = Instant.now()): Instant? {
        cancel(context)
        val settings = SettingsStore.get(context)
        if (settings.announcementsEnabled && settings.summerMask == 0L && settings.winterMask == 0L) {
            SettingsStore.setAnnouncementsEnabled(context, false)
            SettingsStore.setPlannedAt(context, 0)
            return null
        }
        val summerStart = ClockLogic.parseStart(settings.summerStart)
        val winterStart = ClockLogic.parseStart(settings.winterStart)
        val next = if (settings.announcementsEnabled && summerStart != null && winterStart != null) {
            ClockLogic.nextAnnouncement(now, summerStart, winterStart, settings.summerMask, settings.winterMask)
        } else null
        if (next == null || !canSchedule(context)) {
            SettingsStore.setPlannedAt(context, 0)
            return null
        }

        SettingsStore.setPlannedAt(context, next.toEpochMilli())
        return try {
            context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                next.toEpochMilli(),
                pendingIntent(context, next.toEpochMilli()),
            )
            next
        } catch (_: SecurityException) {
            SettingsStore.setPlannedAt(context, 0)
            null
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, 0))
    }

    private fun pendingIntent(context: Context, plannedAtMillis: Long) = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, AnnouncementReceiver::class.java).putExtra(EXTRA_PLANNED_AT, plannedAtMillis),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
