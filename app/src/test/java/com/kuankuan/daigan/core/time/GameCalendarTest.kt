package com.kuankuan.daigan.core.time

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameCalendarTest {

    private val shanghai = GameRefreshRules(
        zoneId = ZoneId.of("Asia/Shanghai"),
        dailyRefresh = LocalTime.of(4, 0),
        weeklyRefreshDay = DayOfWeek.MONDAY,
        weeklyRefreshTime = LocalTime.of(5, 0),
    )

    @Test
    fun `game date follows the configured daily refresh not phone midnight`() {
        // 2026-10-18 03:59 in Shanghai is still game day 10-17.
        assertEquals(
            LocalDate.of(2026, 10, 17),
            GameCalendar.gameDate(Instant.parse("2026-10-17T19:59:00Z"), shanghai),
        )
        // 2026-10-18 04:00 starts game day 10-18.
        assertEquals(
            LocalDate.of(2026, 10, 18),
            GameCalendar.gameDate(Instant.parse("2026-10-17T20:00:00Z"), shanghai),
        )
    }

    @Test
    fun `game date respects an explicit different timezone`() {
        val losAngeles = shanghai.copy(
            zoneId = ZoneId.of("America/Los_Angeles"),
            dailyRefresh = LocalTime.of(5, 0),
        )
        // 2026-10-17 11:59Z = 04:59 in Los Angeles (PDT) -> game day 10-16.
        assertEquals(
            LocalDate.of(2026, 10, 16),
            GameCalendar.gameDate(Instant.parse("2026-10-17T11:59:00Z"), losAngeles),
        )
        assertEquals(
            LocalDate.of(2026, 10, 17),
            GameCalendar.gameDate(Instant.parse("2026-10-17T12:00:00Z"), losAngeles),
        )
    }

    @Test
    fun `hosting dates are inclusive and start day counts as one`() {
        val start = LocalDate.of(2026, 10, 17)
        val end = GameCalendar.hostingEndDate(start, 3)
        assertEquals(LocalDate.of(2026, 10, 19), end)
        assertEquals(3, GameCalendar.inclusiveDurationDays(start, end))
        assertTrue(GameCalendar.isWithinPeriod(LocalDate.of(2026, 10, 19), start, end))
        assertFalse(GameCalendar.isWithinPeriod(LocalDate.of(2026, 10, 20), start, end))
    }

    @Test
    fun `hosting status is active on the end date and expired the day after`() {
        val start = LocalDate.of(2026, 10, 17)
        val end = LocalDate.of(2026, 10, 19)
        assertEquals(HostingStatus.NOT_STARTED, GameCalendar.hostingStatus(start.minusDays(1), start, end))
        assertEquals(HostingStatus.ACTIVE, GameCalendar.hostingStatus(start, start, end))
        assertEquals(HostingStatus.ACTIVE, GameCalendar.hostingStatus(end, start, end))
        assertEquals(HostingStatus.EXPIRED, GameCalendar.hostingStatus(end.plusDays(1), start, end))
    }

    @Test
    fun `weekly window starts at the configured weekday and time`() {
        val window = GameCalendar.weeklyWindow(
            Instant.parse("2026-10-18T04:00:00Z"), // Sunday 12:00 Shanghai
            shanghai,
        )
        assertEquals(LocalDate.of(2026, 10, 12), window.startDate)
        assertEquals(LocalDate.of(2026, 10, 18), window.endDate)
        assertEquals(LocalDate.of(2026, 10, 12), window.gameDateAtStart)
    }

    @Test
    fun `weekly window remains the old one before the weekly refresh hour`() {
        val before = GameCalendar.weeklyWindow(
            Instant.parse("2026-10-18T20:30:00Z"), // Monday 04:30 Shanghai
            shanghai,
        )
        assertEquals(LocalDate.of(2026, 10, 12), before.startDate)

        val after = GameCalendar.weeklyWindow(
            Instant.parse("2026-10-18T21:00:00Z"), // Monday 05:00 Shanghai
            shanghai,
        )
        assertEquals(LocalDate.of(2026, 10, 19), after.startDate)
    }

    @Test
    fun `weekly window can be looked up from a historical game date`() {
        val window = GameCalendar.weeklyWindowForGameDate(
            LocalDate.of(2026, 10, 20),
            shanghai,
        )
        assertEquals(LocalDate.of(2026, 10, 19), window.startDate)
        assertEquals(LocalDate.of(2026, 10, 25), window.endDate)
    }
}
