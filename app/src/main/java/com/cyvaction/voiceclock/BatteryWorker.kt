package com.cyvaction.voiceclock

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class BatteryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        BatteryReminder.check(applicationContext, "worker")
        healAnnouncementAlarm()
        return Result.success()
    }

    private suspend fun healAnnouncementAlarm() {
        val settings = SettingsStore.get(applicationContext)
        if (!settings.announcementsEnabled) return
        val plannedAt = settings.plannedAtMillis
        val now = System.currentTimeMillis()
        if (plannedAt == 0L || now - plannedAt > HEAL_GRACE_MS) {
            AnnouncementScheduler.reschedule(applicationContext)
        }
    }

    companion object {
        private const val UNIQUE_NAME = "battery_check"
        private const val HEAL_GRACE_MS = 5 * 60 * 1000L

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<BatteryWorker>(15, TimeUnit.MINUTES).build(),
            )
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }
}
