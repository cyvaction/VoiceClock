package com.cyvaction.voiceclock

import java.time.Instant

// Simulation stays in memory and uses the same reminder rules as BatteryWorker.
data class SimulationState(
    val count: Int = 0,
    val lastAt: Instant? = null,
    val sleepWindows: Set<Long> = emptySet(),
    val reminded: Boolean = false,
    val message: String = "选择场景后，点击「检查提醒」。",
) {
    fun check(now: Instant, battery: Int, charging: Boolean, settings: AppSettings): SimulationState {
        require(battery in 0..100)
        require(lastAt == null || now >= lastAt)
        if (charging || !ClockLogic.isBelowThreshold(battery, 100, settings.threshold)) {
            return copy(
                count = 0, lastAt = null, reminded = false,
                message = if (charging) "已接上电源，本轮提醒已重置" else "电量充足，本轮提醒已重置",
            )
        }
        if (!settings.batteryEnabled) return copy(reminded = false, message = "低电量提醒已关闭")
        val sleep = if (settings.sleepEnabled) ClockLogic.currentSleepWindowStart(
            now, settings.sleepStartMinutes, settings.sleepEndMinutes,
        )?.toEpochMilli() else null
        val decision = ClockLogic.batteryDecision(
            now = now,
            eligible = true,
            maxReminders = settings.maxReminders,
            intervalMinutes = settings.intervalMinutes,
            sleepEnabled = settings.sleepEnabled,
            sleepStartMinutes = settings.sleepStartMinutes,
            sleepEndMinutes = settings.sleepEndMinutes,
            active = true,
            spokenCount = count,
            lastSpokenAtMillis = lastAt?.toEpochMilli() ?: 0,
            lastSleepWindowStartMillis = sleep?.takeIf { it in sleepWindows } ?: 0,
        )
        val reason = when {
            sleep != null && sleep in sleepWindows -> "本睡眠时段已提醒一次，安心休息"
            count >= settings.maxReminders -> "本轮提醒次数已用完，等待充电或电量恢复"
            !decision.shouldPlay -> "距离上次提醒还没达到 ${settings.intervalMinutes} 分钟"
            sleep != null -> "睡眠时段，仅提醒这一次"
            else -> "电量低于阈值，可以提醒充电"
        }
        return copy(
            count = count + if (decision.shouldPlay) 1 else 0,
            lastAt = if (decision.shouldPlay) now else lastAt,
            sleepWindows = if (decision.shouldPlay && sleep != null) sleepWindows + sleep else sleepWindows,
            reminded = decision.shouldPlay,
            message = reason,
        )
    }
}
