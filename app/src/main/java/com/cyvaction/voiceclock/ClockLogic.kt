package com.cyvaction.voiceclock

import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId

data class BatteryDecision(
    val shouldPlay: Boolean,
    val active: Boolean,
    val spokenCount: Int,
    val lastSpokenAtMillis: Long,
    val sleepWindowStartMillis: Long?,
)

object ClockLogic {
    val beijingZone: ZoneId = ZoneId.of("Asia/Shanghai")

    fun defaultScheduleMask(summer: Boolean): Long =
        (if (summer) listOf(14, 16, 18, 20, 22, 24, 28, 32, 35, 36, 40, 42)
        else listOf(16, 18, 20, 22, 24, 28, 32, 35, 36, 40))
            .fold(0L) { mask, slot -> mask or (1L shl slot) }

    fun seasonStartForMonth(value: String, month: Int): String {
        val day = requireNotNull(parseStart(value)).dayOfMonth
        return MonthDay.of(month, day.coerceAtMost(LocalDate.of(2001, month, 1).lengthOfMonth()))
            .toString().removePrefix("--")
    }

    fun announcementText(slot: Int): String {
        require(slot in 0..47)
        val hour = slot / 2
        val spokenHour = (hour % 12).takeUnless { it == 0 } ?: 12
        return "现在是${if (hour < 12) "上午" else "下午"}${chineseNumber(spokenHour)}点${if (slot % 2 == 0) "整" else "半"}"
    }

    fun batteryText(): String = "手机没电，请及时充电"

    fun parseStart(value: String): MonthDay? = runCatching { MonthDay.parse("--$value") }
        .getOrNull()
        ?.takeUnless { it == MonthDay.of(2, 29) }

    fun startsAreValid(summer: String, winter: String): Boolean {
        val summerStart = parseStart(summer)
        val winterStart = parseStart(winter)
        return summerStart != null && winterStart != null && summerStart != winterStart
    }

    fun isSummer(date: LocalDate, summerStart: MonthDay, winterStart: MonthDay): Boolean {
        require(summerStart != winterStart)
        val day = MonthDay.from(date)
        return if (summerStart < winterStart) day >= summerStart && day < winterStart
        else day >= summerStart || day < winterStart
    }

    fun nextAnnouncement(
        now: Instant,
        summerStart: MonthDay,
        winterStart: MonthDay,
        summerMask: Long,
        winterMask: Long,
    ): Instant? {
        if (summerMask == 0L && winterMask == 0L) return null
        val today = now.atZone(beijingZone).toLocalDate()
        repeat(367) { dayOffset ->
            val date = today.plusDays(dayOffset.toLong())
            val mask = if (isSummer(date, summerStart, winterStart)) summerMask else winterMask
            repeat(48) { slot ->
                if (mask and (1L shl slot) != 0L) {
                    val candidate = date.atStartOfDay(beijingZone).plusMinutes(slot * 30L).toInstant()
                    if (candidate > now) return candidate
                }
            }
        }
        return null
    }

    fun shouldPlay(plannedAt: Instant, receivedAt: Instant): Boolean =
        !receivedAt.isBefore(plannedAt) && receivedAt <= plannedAt.plusSeconds(5 * 60)

    fun announcementSlot(instant: Instant): Int {
        val time = instant.atZone(beijingZone)
        require(time.minute == 0 || time.minute == 30)
        return time.hour * 2 + time.minute / 30
    }

    fun isAnnouncementEnabled(
        instant: Instant,
        summerStart: MonthDay,
        winterStart: MonthDay,
        summerMask: Long,
        winterMask: Long,
    ): Boolean {
        val dateTime = instant.atZone(beijingZone)
        val mask = if (isSummer(dateTime.toLocalDate(), summerStart, winterStart)) summerMask else winterMask
        return mask and (1L shl announcementSlot(instant)) != 0L
    }

    fun isBelowThreshold(level: Int, scale: Int, threshold: Int): Boolean =
        level >= 0 && scale > 0 && threshold in 5..99 &&
            level.toLong() * 100 < threshold.toLong() * scale

    fun batteryDecision(
        now: Instant,
        eligible: Boolean,
        maxReminders: Int,
        intervalMinutes: Int,
        sleepEnabled: Boolean,
        sleepStartMinutes: Int,
        sleepEndMinutes: Int,
        active: Boolean,
        spokenCount: Int,
        lastSpokenAtMillis: Long,
        lastSleepWindowStartMillis: Long,
    ): BatteryDecision {
        require(maxReminders in 1..10)
        require(intervalMinutes in setOf(5, 15, 30, 60))
        require(validSleepTimes(sleepStartMinutes, sleepEndMinutes))
        if (!eligible) return BatteryDecision(false, false, 0, 0, null)

        val count = if (active) spokenCount else 0
        var lastSpokenAt = if (active) lastSpokenAtMillis else 0
        if (lastSpokenAt > now.toEpochMilli()) lastSpokenAt = now.toEpochMilli()
        if (count >= maxReminders) return BatteryDecision(false, true, count, lastSpokenAt, null)

        val sleepWindow = if (sleepEnabled) {
            currentSleepWindowStart(now, sleepStartMinutes, sleepEndMinutes)
        } else null
        val shouldPlay = if (sleepWindow != null) {
            sleepWindow.toEpochMilli() != lastSleepWindowStartMillis
        } else {
            lastSpokenAt == 0L || now.toEpochMilli() - lastSpokenAt >= intervalMinutes * 60_000L
        }
        return BatteryDecision(shouldPlay, true, count, lastSpokenAt, sleepWindow?.toEpochMilli())
    }

    fun validSleepTimes(startMinutes: Int, endMinutes: Int): Boolean =
        startMinutes in 0..<24 * 60 && endMinutes in 0..<24 * 60 &&
            startMinutes % 30 == 0 && endMinutes % 30 == 0 && startMinutes != endMinutes

    fun currentSleepWindowStart(now: Instant, startMinutes: Int, endMinutes: Int): Instant? {
        require(validSleepTimes(startMinutes, endMinutes))
        val dateTime = now.atZone(beijingZone)
        val minuteOfDay = dateTime.hour * 60 + dateTime.minute
        val startDate = when {
            startMinutes < endMinutes && minuteOfDay in startMinutes..<endMinutes -> dateTime.toLocalDate()
            startMinutes > endMinutes && minuteOfDay >= startMinutes -> dateTime.toLocalDate()
            startMinutes > endMinutes && minuteOfDay < endMinutes -> dateTime.toLocalDate().minusDays(1)
            else -> return null
        }
        return startDate.atStartOfDay(beijingZone).plusMinutes(startMinutes.toLong()).toInstant()
    }

    private fun chineseNumber(value: Int): String {
        require(value in 0..99)
        val digits = "零一二三四五六七八九"
        if (value < 10) return digits[value].toString()
        val tens = if (value < 20) "" else digits[value / 10].toString()
        val ones = if (value % 10 == 0) "" else digits[value % 10].toString()
        return "${tens}十$ones"
    }
}
