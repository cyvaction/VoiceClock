package com.cyvaction.voiceclock

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class ResidentNotificationTest {
    @Test
    fun pausedAndMissingPlanHaveDifferentMessages() {
        assertEquals("报时已暂停", residentNotificationText(false, 0))
        assertEquals("报时已暂停", residentNotificationText(false, 1791930600000))
        assertEquals("暂无可用报时计划", residentNotificationText(true, 0))
    }

    @Test
    fun planUsesBeijingDateAnd24HourTimeRegardlessOfDeviceZone() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals("下次报时 · 10月14日 00:30", residentNotificationText(
                true, Instant.parse("2026-10-13T16:30:00Z").toEpochMilli(),
            ))
            assertEquals("下次报时 · 1月1日 14:00", residentNotificationText(
                true, Instant.parse("2027-01-01T06:00:00Z").toEpochMilli(),
            ))
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
