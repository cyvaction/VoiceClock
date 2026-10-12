package com.cyvaction.voiceclock

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.TimeUnit

class BatteryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = SettingsStore.get(applicationContext)
        if (settings.batteryEnabled && Build.VERSION.SDK_INT < 35) {
            runBatteryCheck(settings)
        }
        healAnnouncementAlarm()
        return Result.success()
    }

    private suspend fun runBatteryCheck(settings: AppSettings) {
        val checkedAt = Instant.now()
        SettingsStore.recordBatteryCheck(applicationContext, checkedAt.toEpochMilli())
        val snapshot = readSnapshot() ?: return
        val decision = ClockLogic.batteryDecision(
            now = checkedAt,
            eligible = snapshot.isEligible(settings.threshold),
            maxReminders = settings.maxReminders,
            intervalMinutes = settings.intervalMinutes,
            sleepEnabled = settings.sleepEnabled,
            sleepStartMinutes = settings.sleepStartMinutes,
            sleepEndMinutes = settings.sleepEndMinutes,
            active = settings.batteryRoundActive,
            spokenCount = settings.spokenCount,
            lastSpokenAtMillis = settings.lastSpokenAtMillis,
            lastSleepWindowStartMillis = settings.lastSleepWindowStartMillis,
        )
        if (!SettingsStore.applyBatteryDecision(applicationContext, settings.batteryRevision, decision) ||
            !decision.shouldPlay
        ) return

        val wakeLock = applicationContext.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceClock:battery")
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        val played = try {
            withTimeoutOrNull(15_000) {
                VoicePlayer.play(applicationContext, R.raw.battery_low) {
                    val latest = SettingsStore.get(applicationContext)
                    latest.batteryEnabled && latest.batteryRevision == settings.batteryRevision &&
                        latest.batteryRoundActive && latest.spokenCount == decision.spokenCount &&
                        readSnapshot()?.isEligible(latest.threshold) == true
                }
            } == true
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
        if (played) {
            SettingsStore.commitBatterySuccess(
                applicationContext,
                settings.batteryRevision,
                decision.spokenCount,
                System.currentTimeMillis(),
                decision.sleepWindowStartMillis,
            )
        }
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

    private fun readSnapshot(): BatterySnapshot? {
        val intent = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        if (level < 0 || scale <= 0 || plugged < 0 || status !in VALID_STATUSES) return null
        return BatterySnapshot(level, scale, plugged, status)
    }

    private data class BatterySnapshot(val level: Int, val scale: Int, val plugged: Int, val status: Int) {
        fun isEligible(threshold: Int): Boolean =
            plugged == 0 && status != BatteryManager.BATTERY_STATUS_CHARGING &&
                status != BatteryManager.BATTERY_STATUS_FULL &&
                ClockLogic.isBelowThreshold(level, scale, threshold)
    }

    companion object {
        private const val UNIQUE_NAME = "battery_check"
        private const val WAKE_LOCK_TIMEOUT_MS = 17_000L
        private const val HEAL_GRACE_MS = 5 * 60 * 1000L
        private val VALID_STATUSES = setOf(
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_DISCHARGING,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL,
        )

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
