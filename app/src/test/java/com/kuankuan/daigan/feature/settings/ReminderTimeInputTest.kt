package com.kuankuan.daigan.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderTimeInputTest {
    @Test
    fun parsesOnlyValidTwentyFourHourTimes() {
        assertEquals(0, parseReminderTimeInput("00:00"))
        assertEquals(1439, parseReminderTimeInput("23:59"))
        assertEquals(540, parseReminderTimeInput("09:00"))
        assertNull(parseReminderTimeInput(""))
        assertNull(parseReminderTimeInput("1:00"))
        assertNull(parseReminderTimeInput("12:3"))
        assertNull(parseReminderTimeInput("24:00"))
        assertNull(parseReminderTimeInput("12:60"))
        assertNull(parseReminderTimeInput("ab:cd"))
        assertNull(parseReminderTimeInput("123"))
    }
}
