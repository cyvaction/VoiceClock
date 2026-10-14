package com.cyvaction.voiceclock

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay

class ClockLogicTest {
    @Test
    fun demo4MigrationPreservesOldEmptySchedulesAndSeedsOnlyNewInstalls() = runBlocking {
        val summer = longPreferencesKey("summer_mask")
        val winter = longPreferencesKey("winter_mask")
        val threshold = intPreferencesKey("threshold")
        val migration = SettingsStore.scheduleDefaultsMigration
        val fresh = migration.migrate(emptyPreferences())
        assertEquals(ClockLogic.defaultScheduleMask(true), fresh[summer])
        assertEquals(ClockLogic.defaultScheduleMask(false), fresh[winter])
        assertFalse(migration.shouldMigrate(fresh))

        val old = preferencesOf(summer to (1L shl 35), threshold to 30)
        assertTrue(migration.shouldMigrate(old))
        val upgraded = migration.migrate(old)
        assertEquals(1L shl 35, upgraded[summer])
        assertEquals(0L, upgraded[winter])
        assertEquals(30, upgraded[threshold])
        assertFalse(migration.shouldMigrate(upgraded))
        val emptyOldSchedule = migration.migrate(preferencesOf(threshold to 30))
        assertEquals(0L, emptyOldSchedule[summer])
        assertEquals(0L, emptyOldSchedule[winter])
        assertEquals(0L, migration.migrate(preferencesOf(summer to 0L, winter to 0L))[summer])
    }

    @Test
    fun demo4DefaultsKeepFeaturesOffAndConstrainRecurringDates() {
        val defaults = AppSettings()
        assertFalse(defaults.announcementsEnabled)
        assertFalse(defaults.batteryEnabled)
        assertEquals(listOf(14, 16, 18, 20, 22, 24, 28, 32, 35, 36, 40, 42),
            (0..47).filter { defaults.summerMask and (1L shl it) != 0L })
        assertEquals(listOf(16, 18, 20, 22, 24, 28, 32, 35, 36, 40),
            (0..47).filter { defaults.winterMask and (1L shl it) != 0L })
        assertEquals("02-28", ClockLogic.seasonStartForMonth("01-31", 2))
        assertEquals("04-30", ClockLogic.seasonStartForMonth("01-31", 4))
        assertEquals("12-01", ClockLogic.seasonStartForMonth("06-01", 12))
        for (month in 1..12) {
            assertNotNull(ClockLogic.parseStart(ClockLogic.seasonStartForMonth("01-31", month)))
        }
    }

    @Test
    fun demo4SimulationHonorsThresholdIntervalCountAndChargingWithoutUsingLiveCounters() {
        val settings = AppSettings(batteryEnabled = true, spokenCount = 2, batteryRoundActive = true)
        val noon = Instant.parse("2026-09-21T04:00:00Z")
        var round = SimulationState().check(noon, 25, false, settings)
        assertEquals(0, round.count)
        assertFalse(round.reminded)
        round = round.check(noon, 24, false, settings)
        assertEquals(1, round.count)
        assertTrue(round.reminded)
        assertFalse(round.check(noon.plusSeconds(14 * 60), 24, false, settings).reminded)
        round = round.check(noon.plusSeconds(15 * 60), 24, false, settings)
        assertEquals(2, round.count)
        assertTrue(round.reminded)
        round = round.check(noon.plusSeconds(30 * 60), 24, false, settings)
        assertEquals(3, round.count)
        assertFalse(round.check(noon.plusSeconds(60 * 60), 24, false, settings).reminded)
        round = round.check(noon.plusSeconds(60 * 60), 24, true, settings)
        assertEquals(0, round.count)
        assertNull(round.lastAt)
        round = round.check(noon.plusSeconds(60 * 60), 24, false, settings)
        assertTrue(round.reminded)
        assertEquals(0, round.check(noon.plusSeconds(60 * 60), 25, false, settings).count)
        assertEquals(2, settings.spokenCount)
    }

    @Test
    fun demo4SimulationRetainsSleepLimitAcrossMidnightAndCharging() {
        val settings = AppSettings(batteryEnabled = true)
        val night = Instant.parse("2026-09-21T14:00:00Z")
        var round = SimulationState().check(night.minusSeconds(60), 24, false, settings)
        round = round.check(night, 24, false, settings)
        assertTrue(round.reminded) // Sleep ignores the daytime repeat interval.
        assertEquals(2, round.count)
        val midnight = night.plusSeconds(3 * 60 * 60)
        assertFalse(round.check(midnight, 24, false, settings).reminded)
        round = round.check(midnight, 24, true, settings)
        assertEquals(0, round.count)
        assertFalse(round.check(midnight, 24, false, settings).reminded)
        round = round.check(night.plusSeconds(9 * 60 * 60), 24, false, settings)
        assertTrue(round.reminded)
        round = round.check(night.plusSeconds(24 * 60 * 60), 24, false, settings)
        assertTrue(round.reminded)
        assertEquals(2, round.sleepWindows.size)
    }

    @Test
    fun demo4SimulationSupportsDisabledRulesAndRejectsClockRollback() {
        val settings = AppSettings(batteryEnabled = true)
        val now = Instant.parse("2026-09-21T14:00:00Z")
        val round = SimulationState().check(now, 24, false, settings)
        val disabled = round.check(now, 24, false, settings.copy(batteryEnabled = false))
        assertFalse(disabled.reminded)
        assertEquals(1, disabled.count)
        assertTrue(round.check(now.plusSeconds(15 * 60), 24, false, settings.copy(sleepEnabled = false)).reminded)
        assertThrows(IllegalArgumentException::class.java) { round.check(now.minusSeconds(1), 24, false, settings) }
        assertThrows(IllegalArgumentException::class.java) { round.check(now, 101, false, settings) }
    }

    @Test
    fun generatesEveryRequiredPhrase() {
        val timePhrases = (0..47).map(ClockLogic::announcementText)
        assertEquals(48, timePhrases.distinct().size)
        assertEquals("现在时间，晚上12点整", timePhrases[0])
        assertEquals("现在时间，凌晨12点半", timePhrases[1])
        assertEquals("现在时间，凌晨5点半", timePhrases[11])
        assertEquals("现在时间，上午6点整", timePhrases[12])
        assertEquals("现在时间，上午10点整", timePhrases[20])
        assertEquals("现在时间，上午11点半", timePhrases[23])
        assertEquals("现在时间，中午12点整", timePhrases[24])
        assertEquals("现在时间，中午12点半", timePhrases[25])
        assertEquals("现在时间，下午1点半", timePhrases[27])
        assertEquals("现在时间，下午5点半", timePhrases[35])
        assertEquals("现在时间，晚上6点整", timePhrases[36])
        assertEquals("现在时间，晚上9点整", timePhrases[42])
        assertEquals("现在时间，晚上11点半", timePhrases[47])

        assertEquals("手机没电，请及时充电", ClockLogic.batteryText())
    }

    @Test
    fun validatesAndPartitionsSeasonStarts() {
        assertTrue(ClockLogic.startsAreValid("06-01", "12-01"))
        assertFalse(ClockLogic.startsAreValid("02-29", "12-01"))
        assertFalse(ClockLogic.startsAreValid("06-01", "06-01"))
        assertFalse(ClockLogic.startsAreValid("04-31", "12-01"))

        val summer = MonthDay.of(6, 1)
        val winter = MonthDay.of(12, 1)
        for (year in listOf(2027, 2028)) {
            var day = LocalDate.of(year, 1, 1)
            while (day.year == year) {
                assertEquals(day.monthValue in 6..11, ClockLogic.isSummer(day, summer, winter))
                day = day.plusDays(1)
            }
        }
    }

    @Test
    fun findsTheNextEnabledHalfHourAcrossSeasonsAndYears() {
        val summer = MonthDay.of(6, 1)
        val winter = MonthDay.of(12, 1)
        val result = ClockLogic.nextAnnouncement(
            Instant.parse("2026-11-30T15:59:00Z"),
            summer,
            winter,
            1L shl 35,
            1L,
        )
        assertNotNull(result)
        assertEquals(Instant.parse("2026-11-30T16:00:00Z"), result)
        assertEquals(
            Instant.parse("2026-11-30T16:00:00Z"),
            ClockLogic.nextAnnouncement(
                Instant.parse("2026-06-01T00:00:00Z"),
                summer,
                winter,
                0L,
                1L,
            ),
        )
        assertNull(ClockLogic.nextAnnouncement(Instant.now(), summer, winter, 0L, 0L))
    }

    @Test
    fun acceptsExactlyFiveMinutesLate() {
        val planned = Instant.parse("2026-09-08T12:00:00Z")
        assertTrue(ClockLogic.shouldPlay(planned, planned.plusSeconds(300)))
        assertFalse(ClockLogic.shouldPlay(planned, planned.plusSeconds(301)))
        assertFalse(ClockLogic.shouldPlay(planned, planned.minusSeconds(1)))
        assertEquals(0, ClockLogic.announcementSlot(Instant.parse("2026-09-08T16:00:00Z")))
        assertEquals(35, ClockLogic.announcementSlot(Instant.parse("2026-09-08T09:30:00Z")))
    }

    @Test
    fun rechecksThePlannedSlotAgainstTheLatestSeasonSchedule() {
        val summer = MonthDay.of(6, 1)
        val winter = MonthDay.of(12, 1)
        val summerTime = Instant.parse("2026-09-08T09:30:00Z")
        val winterTime = Instant.parse("2026-12-08T16:00:00Z")

        assertTrue(ClockLogic.isAnnouncementEnabled(summerTime, summer, winter, 1L shl 35, 0L))
        assertFalse(ClockLogic.isAnnouncementEnabled(summerTime, summer, winter, 0L, 1L shl 35))
        assertTrue(ClockLogic.isAnnouncementEnabled(winterTime, summer, winter, 0L, 1L))
    }

    @Test
    fun batteryThresholdIsStrictAndRejectsInvalidSnapshots() {
        assertTrue(ClockLogic.isBelowThreshold(level = 24, scale = 100, threshold = 25))
        assertFalse(ClockLogic.isBelowThreshold(level = 25, scale = 100, threshold = 25))
        assertFalse(ClockLogic.isBelowThreshold(level = -1, scale = 100, threshold = 25))
        assertFalse(ClockLogic.isBelowThreshold(level = 0, scale = 0, threshold = 25))
        assertTrue(ClockLogic.isBelowThreshold(level = 249, scale = 1000, threshold = 25))
    }

    @Test
    fun batteryRoundResetsAndHonorsCountIntervalAndClockRollback() {
        val now = Instant.parse("2026-09-08T12:00:00Z")
        assertTrue(decision(now, active = false).shouldPlay)
        assertFalse(decision(now.plusSeconds(299), active = true, count = 1, lastSpokenAt = now.toEpochMilli()).shouldPlay)
        assertTrue(decision(now.plusSeconds(300), active = true, count = 1, lastSpokenAt = now.toEpochMilli()).shouldPlay)
        assertFalse(decision(now, active = true, count = 3).shouldPlay)

        val reset = decision(now, eligible = false, active = true, count = 2, lastSpokenAt = now.toEpochMilli())
        assertFalse(reset.active)
        assertEquals(0, reset.spokenCount)
        assertEquals(0, reset.lastSpokenAtMillis)

        val rolledBack = decision(now, active = true, count = 1, lastSpokenAt = now.plusSeconds(60).toEpochMilli())
        assertEquals(now.toEpochMilli(), rolledBack.lastSpokenAtMillis)
        assertFalse(rolledBack.shouldPlay)
    }

    @Test
    fun sleepWindowAllowsOnlyOneAttemptAndHandlesBoundaries() {
        val start = Instant.parse("2026-09-08T14:00:00Z")
        val windowStart = ClockLogic.currentSleepWindowStart(start, 22 * 60, 7 * 60)
        assertEquals(start, windowStart)
        assertEquals(start, ClockLogic.currentSleepWindowStart(Instant.parse("2026-09-08T22:59:59Z"), 22 * 60, 7 * 60))
        assertNull(ClockLogic.currentSleepWindowStart(Instant.parse("2026-09-08T23:00:00Z"), 22 * 60, 7 * 60))

        val first = decision(start, active = false)
        assertTrue(first.shouldPlay)
        assertEquals(start.toEpochMilli(), first.sleepWindowStartMillis)
        val sameNight = decision(start.plusSeconds(60), active = false, lastSleepWindow = start.toEpochMilli())
        assertFalse(sameNight.shouldPlay)
        assertTrue(ClockLogic.validSleepTimes(22 * 60, 7 * 60))
        assertFalse(ClockLogic.validSleepTimes(22 * 60 + 15, 7 * 60))
        assertFalse(ClockLogic.validSleepTimes(22 * 60, 22 * 60))
    }

    @Test
    fun sleepReminderCountsTowardTheRoundAndDaytimeContinuesRemainingCount() {
        val nightStart = Instant.parse("2026-09-08T14:00:00Z")
        val nightDecision = ClockLogic.batteryDecision(
            now = nightStart,
            eligible = true,
            maxReminders = 3,
            intervalMinutes = 30,
            sleepEnabled = true,
            sleepStartMinutes = 22 * 60,
            sleepEndMinutes = 7 * 60,
            active = true,
            spokenCount = 1,
            lastSpokenAtMillis = nightStart.minusSeconds(5 * 60).toEpochMilli(),
            lastSleepWindowStartMillis = 0,
        )
        assertTrue(nightDecision.shouldPlay)

        val sameNight = ClockLogic.batteryDecision(
            now = nightStart.plusSeconds(60),
            eligible = true,
            maxReminders = 3,
            intervalMinutes = 30,
            sleepEnabled = true,
            sleepStartMinutes = 22 * 60,
            sleepEndMinutes = 7 * 60,
            active = true,
            spokenCount = 2,
            lastSpokenAtMillis = nightStart.toEpochMilli(),
            lastSleepWindowStartMillis = nightStart.toEpochMilli(),
        )
        assertFalse(sameNight.shouldPlay)

        val afterWake = sameNight.copyFromStateAt(Instant.parse("2026-09-08T23:00:00Z"))
        assertTrue(afterWake.shouldPlay)
        assertEquals(2, afterWake.spokenCount)

        val resetSameNight = decision(
            nightStart.plusSeconds(120),
            active = false,
            lastSleepWindow = nightStart.toEpochMilli(),
        )
        assertFalse(resetSameNight.shouldPlay)
    }

    private fun BatteryDecision.copyFromStateAt(now: Instant) = ClockLogic.batteryDecision(
        now = now,
        eligible = true,
        maxReminders = 3,
        intervalMinutes = 30,
        sleepEnabled = true,
        sleepStartMinutes = 22 * 60,
        sleepEndMinutes = 7 * 60,
        active = active,
        spokenCount = spokenCount,
        lastSpokenAtMillis = lastSpokenAtMillis,
        lastSleepWindowStartMillis = sleepWindowStartMillis ?: 0,
    )

    private fun decision(
        now: Instant,
        eligible: Boolean = true,
        active: Boolean,
        count: Int = 0,
        lastSpokenAt: Long = 0,
        lastSleepWindow: Long = 0,
    ) = ClockLogic.batteryDecision(
        now = now,
        eligible = eligible,
        maxReminders = 3,
        intervalMinutes = 5,
        sleepEnabled = true,
        sleepStartMinutes = 22 * 60,
        sleepEndMinutes = 7 * 60,
        active = active,
        spokenCount = count,
        lastSpokenAtMillis = lastSpokenAt,
        lastSleepWindowStartMillis = lastSleepWindow,
    )
}
