package com.cyvaction.voiceclock

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("voice_clock")

data class AppSettings(
    val announcementsEnabled: Boolean = false,
    val summerStart: String = "06-01",
    val winterStart: String = "12-01",
    val summerMask: Long = 0,
    val winterMask: Long = 0,
    val plannedAtMillis: Long = 0,
    val batteryEnabled: Boolean = false,
    val threshold: Int = 25,
    val maxReminders: Int = 3,
    val intervalMinutes: Int = 15,
    val sleepEnabled: Boolean = true,
    val sleepStartMinutes: Int = 22 * 60,
    val sleepEndMinutes: Int = 7 * 60,
    val batteryRoundActive: Boolean = false,
    val spokenCount: Int = 0,
    val lastSpokenAtMillis: Long = 0,
    val lastSleepWindowStartMillis: Long = 0,
    val lastCheckedAtMillis: Long = 0,
    val batteryRevision: Long = 0,
)

object SettingsStore {
    private val announcementsEnabledKey = booleanPreferencesKey("announcements_enabled")
    private val summerStartKey = stringPreferencesKey("summer_start")
    private val winterStartKey = stringPreferencesKey("winter_start")
    private val summerMaskKey = longPreferencesKey("summer_mask")
    private val winterMaskKey = longPreferencesKey("winter_mask")
    private val plannedAtMillisKey = longPreferencesKey("planned_at")
    private val batteryEnabledKey = booleanPreferencesKey("battery_enabled")
    private val thresholdKey = intPreferencesKey("threshold")
    private val maxRemindersKey = intPreferencesKey("max_reminders")
    private val intervalMinutesKey = intPreferencesKey("interval_minutes")
    private val sleepEnabledKey = booleanPreferencesKey("sleep_enabled")
    private val sleepStartMinutesKey = intPreferencesKey("sleep_start_minutes")
    private val sleepEndMinutesKey = intPreferencesKey("sleep_end_minutes")
    private val batteryRoundActiveKey = booleanPreferencesKey("battery_round_active")
    private val spokenCountKey = intPreferencesKey("spoken_count")
    private val lastSpokenAtMillisKey = longPreferencesKey("last_spoken_at")
    private val lastSleepWindowStartMillisKey = longPreferencesKey("last_sleep_window_start")
    private val lastCheckedAtMillisKey = longPreferencesKey("last_checked_at")
    private val batteryRevisionKey = longPreferencesKey("battery_revision")
    private val validMask = (1L shl 48) - 1

    fun flow(context: Context) = context.dataStore.data.map { values ->
        AppSettings(
            announcementsEnabled = values[announcementsEnabledKey] ?: false,
            summerStart = values[summerStartKey] ?: "06-01",
            winterStart = values[winterStartKey] ?: "12-01",
            summerMask = values[summerMaskKey] ?: 0,
            winterMask = values[winterMaskKey] ?: 0,
            plannedAtMillis = values[plannedAtMillisKey] ?: 0,
            batteryEnabled = values[batteryEnabledKey] ?: false,
            threshold = (values[thresholdKey] ?: 25).coerceIn(5, 99),
            maxReminders = (values[maxRemindersKey] ?: 3).coerceIn(1, 10),
            intervalMinutes = (values[intervalMinutesKey] ?: 15).takeIf { it in setOf(5, 15, 30, 60) } ?: 15,
            sleepEnabled = values[sleepEnabledKey] ?: true,
            sleepStartMinutes = values[sleepStartMinutesKey] ?: 22 * 60,
            sleepEndMinutes = values[sleepEndMinutesKey] ?: 7 * 60,
            batteryRoundActive = values[batteryRoundActiveKey] ?: false,
            spokenCount = values[spokenCountKey] ?: 0,
            lastSpokenAtMillis = values[lastSpokenAtMillisKey] ?: 0,
            lastSleepWindowStartMillis = values[lastSleepWindowStartMillisKey] ?: 0,
            lastCheckedAtMillis = values[lastCheckedAtMillisKey] ?: 0,
            batteryRevision = values[batteryRevisionKey] ?: 0,
        )
    }

    suspend fun get(context: Context): AppSettings = flow(context).first()

    suspend fun setAnnouncementsEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[announcementsEnabledKey] = enabled }
    }

    suspend fun setSeasonStarts(context: Context, summer: String, winter: String) {
        require(ClockLogic.startsAreValid(summer, winter))
        context.dataStore.edit {
            it[summerStartKey] = summer
            it[winterStartKey] = winter
        }
    }

    suspend fun toggleScheduleSlot(context: Context, summer: Boolean, slot: Int) {
        require(slot in 0..47)
        context.dataStore.edit {
            val key = if (summer) summerMaskKey else winterMaskKey
            it[key] = ((it[key] ?: 0L) xor (1L shl slot)) and validMask
        }
    }

    suspend fun setPlannedAt(context: Context, plannedAtMillis: Long) {
        context.dataStore.edit { it[plannedAtMillisKey] = plannedAtMillis }
    }

    suspend fun setBatteryEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit {
            if ((it[batteryEnabledKey] ?: false) != enabled) {
                it[batteryEnabledKey] = enabled
                bumpBatteryRevision(it)
                resetBatteryRound(it)
            }
        }
    }

    suspend fun setThreshold(context: Context, value: Int) {
        require(value in 5..99)
        context.dataStore.edit {
            if ((it[thresholdKey] ?: 25) != value) {
                it[thresholdKey] = value
                bumpBatteryRevision(it)
                resetBatteryRound(it)
            }
        }
    }

    suspend fun setMaxReminders(context: Context, value: Int) {
        require(value in 1..10)
        context.dataStore.edit {
            if ((it[maxRemindersKey] ?: 3) != value) {
                it[maxRemindersKey] = value
                bumpBatteryRevision(it)
            }
        }
    }

    suspend fun setIntervalMinutes(context: Context, value: Int) {
        require(value in setOf(5, 15, 30, 60))
        context.dataStore.edit {
            if ((it[intervalMinutesKey] ?: 15) != value) {
                it[intervalMinutesKey] = value
                bumpBatteryRevision(it)
            }
        }
    }

    suspend fun setSleepEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit {
            if ((it[sleepEnabledKey] ?: true) != enabled) {
                it[sleepEnabledKey] = enabled
                bumpBatteryRevision(it)
            }
        }
    }

    suspend fun setSleepTimes(context: Context, startMinutes: Int, endMinutes: Int) {
        require(ClockLogic.validSleepTimes(startMinutes, endMinutes))
        context.dataStore.edit {
            if ((it[sleepStartMinutesKey] ?: 22 * 60) != startMinutes ||
                (it[sleepEndMinutesKey] ?: 7 * 60) != endMinutes
            ) {
                it[sleepStartMinutesKey] = startMinutes
                it[sleepEndMinutesKey] = endMinutes
                bumpBatteryRevision(it)
            }
        }
    }

    suspend fun recordBatteryCheck(context: Context, checkedAt: Long) {
        context.dataStore.edit { it[lastCheckedAtMillisKey] = checkedAt }
    }

    suspend fun applyBatteryDecision(
        context: Context,
        expectedRevision: Long,
        decision: BatteryDecision,
    ): Boolean {
        var applied = false
        context.dataStore.edit {
            if ((it[batteryEnabledKey] ?: false) && (it[batteryRevisionKey] ?: 0) == expectedRevision) {
                it[batteryRoundActiveKey] = decision.active
                it[spokenCountKey] = decision.spokenCount
                it[lastSpokenAtMillisKey] = decision.lastSpokenAtMillis
                applied = true
            }
        }
        return applied
    }

    suspend fun commitBatterySuccess(
        context: Context,
        expectedRevision: Long,
        expectedSpokenCount: Int,
        spokenAtMillis: Long,
        sleepWindowStartMillis: Long?,
    ): Boolean {
        var committed = false
        context.dataStore.edit {
            if ((it[batteryEnabledKey] ?: false) &&
                (it[batteryRevisionKey] ?: 0) == expectedRevision &&
                (it[batteryRoundActiveKey] ?: false) &&
                (it[spokenCountKey] ?: 0) == expectedSpokenCount
            ) {
                it[spokenCountKey] = expectedSpokenCount + 1
                it[lastSpokenAtMillisKey] = spokenAtMillis
                if (sleepWindowStartMillis != null) {
                    it[lastSleepWindowStartMillisKey] = sleepWindowStartMillis
                }
                committed = true
            }
        }
        return committed
    }

    private fun bumpBatteryRevision(values: MutablePreferences) {
        values[batteryRevisionKey] = (values[batteryRevisionKey] ?: 0) + 1
    }

    private fun resetBatteryRound(values: MutablePreferences) {
        values[batteryRoundActiveKey] = false
        values[spokenCountKey] = 0
        values[lastSpokenAtMillisKey] = 0
    }
}
