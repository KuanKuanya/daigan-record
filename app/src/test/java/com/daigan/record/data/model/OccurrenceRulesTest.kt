package com.daigan.record.data.model

import com.daigan.record.core.time.GameRefreshRules
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OccurrenceRulesTest {
    @Test
    fun `monthly fixed date skips missing days and respects period`() {
        val start = LocalDate.of(2026, 1, 1)
        fun slot(date: LocalDate, day: Int = 31) = OccurrenceRules.slotForDate(
            RecurrenceType.MONTHLY, emptySet(), null, start, LocalDate.of(2026, 12, 31),
            date, rules, monthDay = day,
        )
        assertEquals("M:2026-01-31", slot(LocalDate.of(2026, 1, 31))?.key)
        assertNull(slot(LocalDate.of(2026, 2, 28)))
        assertNull(slot(LocalDate.of(2026, 4, 30)))
        assertEquals("M:2026-03-31", slot(LocalDate.of(2026, 3, 31))?.key)
        assertNull(slot(LocalDate.of(2026, 3, 31), 32))
    }


    @Test
    fun `monthly game day changes at refresh and stays inside hosting period`() {
        val date = LocalDate.of(2028, 2, 29)
        fun slot(now: Instant, end: LocalDate = date) = OccurrenceRules.slotForNow(
            RecurrenceType.MONTHLY, emptySet(), null, date, end,
            com.daigan.record.core.time.GameCalendar.gameDate(now, rules), rules, now, monthDay = 29,
        )
        assertNull(slot(Instant.parse("2028-02-28T19:59:59Z")))
        assertEquals("M:2028-02-29", slot(Instant.parse("2028-02-28T20:00:00Z"))?.key)
        assertNull(slot(Instant.parse("2028-02-29T20:00:00Z")))
    }

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

    @Test
    fun `a one-time template task never produces a hosting occurrence`() {
        val date = LocalDate.of(2026, 10, 19)
        assertFalse(
            OccurrenceRules.appliesOn(
                RecurrenceType.ONE_TIME,
                emptySet(),
                null,
                periodStart,
                date,
            ),
        )
        assertNull(
            OccurrenceRules.slotForDate(
                RecurrenceType.ONE_TIME,
                emptySet(),
                null,
                periodStart,
                periodEnd,
                date,
                rules,
            ),
        )
        assertNull(
            OccurrenceRules.slotForNow(
                RecurrenceType.ONE_TIME,
                emptySet(),
                null,
                periodStart,
                periodEnd,
                date,
                rules,
                Instant.parse("2026-10-19T02:00:00Z"),
            ),
        )
        assertTrue(
            OccurrenceRules.slotsForDate(
                RecurrenceType.ONE_TIME,
                emptySet(),
                null,
                periodStart,
                periodEnd,
                date,
                rules,
            ).isEmpty(),
        )
    }
}
