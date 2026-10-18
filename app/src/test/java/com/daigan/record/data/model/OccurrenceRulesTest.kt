package com.daigan.record.data.model

import com.daigan.record.core.time.GameRefreshRules
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OccurrenceRulesTest {

    private val rules = GameRefreshRules(
        zoneId = ZoneId.of("Asia/Shanghai"),
        dailyRefresh = LocalTime.of(4, 0),
        weeklyRefreshDay = DayOfWeek.MONDAY,
        weeklyRefreshTime = LocalTime.of(5, 0),
    )
    private val periodStart = LocalDate.of(2026, 10, 12)
    private val periodEnd = LocalDate.of(2026, 10, 25)

    @Test
    fun `daily task gets one slot per game date`() {
        val monday = OccurrenceRules.slotForDate(
            RecurrenceType.DAILY,
            emptySet(),
            null,
            periodStart,
            periodEnd,
            LocalDate.of(2026, 10, 19),
            rules,
        )
        val tuesday = OccurrenceRules.slotForDate(
            RecurrenceType.DAILY,
            emptySet(),
            null,
            periodStart,
            periodEnd,
            LocalDate.of(2026, 10, 20),
            rules,
        )
        assertNotNull(monday)
        assertNotNull(tuesday)
        assertNotEquals(monday!!.key, tuesday!!.key)
        assertEquals("D:2026-10-19", monday.key)
    }

    @Test
    fun `selected weekdays only match the chosen days`() {
        val monday = OccurrenceRules.slotForDate(
            RecurrenceType.SELECTED_WEEKDAYS,
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            null,
            periodStart,
            periodEnd,
            LocalDate.of(2026, 10, 19),
            rules,
        )
        val tuesday = OccurrenceRules.slotForDate(
            RecurrenceType.SELECTED_WEEKDAYS,
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            null,
            periodStart,
            periodEnd,
            LocalDate.of(2026, 10, 20),
            rules,
        )
        assertNotNull(monday)
        assertNull(tuesday)
    }

    @Test
    fun `every n days is anchored to the hosting start date`() {
        fun slot(date: LocalDate) = OccurrenceRules.slotForDate(
            RecurrenceType.EVERY_N_DAYS,
            emptySet(),
            3,
            periodStart,
            periodEnd,
            date,
            rules,
        )
        assertNotNull(slot(LocalDate.of(2026, 10, 12)))
        assertNull(slot(LocalDate.of(2026, 10, 13)))
        assertNotNull(slot(LocalDate.of(2026, 10, 15)))
        assertNotNull(slot(LocalDate.of(2026, 10, 18)))
        assertEquals("E:2026-10-15", slot(LocalDate.of(2026, 10, 15))!!.key)
    }

    @Test
    fun `weekly work has exactly one stable occurrence per window`() {
        val monday = OccurrenceRules.slotForDate(
            RecurrenceType.WEEKLY,
            emptySet(),
            null,
            periodStart,
            periodEnd,
            LocalDate.of(2026, 10, 19),
            rules,
        )
        val tuesday = OccurrenceRules.slotForDate(
            RecurrenceType.WEEKLY,
            emptySet(),
            null,
            periodStart,
            periodEnd,
            LocalDate.of(2026, 10, 20),
            rules,
        )
        val sunday = OccurrenceRules.slotForDate(
            RecurrenceType.WEEKLY,
            emptySet(),
            null,
            periodStart,
            periodEnd,
            LocalDate.of(2026, 10, 25),
            rules,
        )
        assertNotNull(monday)
        assertEquals("W:2026-10-19", monday!!.key)
        assertEquals(monday.key, tuesday!!.key)
        assertEquals(monday.key, sunday!!.key)
    }

    @Test
    fun `tasks never exist outside the hosting period`() {
        assertNull(
            OccurrenceRules.slotForDate(
                RecurrenceType.DAILY,
                emptySet(),
                null,
                periodStart,
                periodEnd,
                periodStart.minusDays(1),
                rules,
            ),
        )
        assertNull(
            OccurrenceRules.slotForDate(
                RecurrenceType.DAILY,
                emptySet(),
                null,
                periodStart,
                periodEnd,
                periodEnd.plusDays(1),
                rules,
            ),
        )
        assertNotNull(
            OccurrenceRules.slotForDate(
                RecurrenceType.DAILY,
                emptySet(),
                null,
                periodStart,
                periodEnd,
                periodEnd,
                rules,
            ),
        )
    }

    @Test
    fun `a future start produces no work for today`() {
        assertNull(
            OccurrenceRules.slotForNow(
                RecurrenceType.DAILY,
                emptySet(),
                null,
                periodStart = LocalDate.of(2026, 10, 20),
                periodEnd = LocalDate.of(2026, 10, 30),
                today = LocalDate.of(2026, 10, 19),
                rules = rules,
                now = Instant.parse("2026-10-19T02:00:00Z"),
            ),
        )
    }

    @Test
    fun `weekly window is clipped to the hosting period but keeps a stable key`() {
        val slot = OccurrenceRules.slotForDate(
            RecurrenceType.WEEKLY,
            emptySet(),
            null,
            periodStart = LocalDate.of(2026, 10, 14),
            periodEnd = LocalDate.of(2026, 10, 20),
            date = LocalDate.of(2026, 10, 14),
            rules = rules,
        )
        assertNotNull(slot)
        assertEquals("W:2026-10-12", slot!!.key)
        assertEquals(LocalDate.of(2026, 10, 14), slot.attributedDate)
        assertEquals(LocalDate.of(2026, 10, 18), slot.windowEnd)
    }
}
