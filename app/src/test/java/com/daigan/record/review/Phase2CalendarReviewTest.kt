package com.daigan.record.review

import com.daigan.record.core.time.GameCalendar
import com.daigan.record.core.time.GameRefreshRules
import com.daigan.record.data.model.OccurrenceRules
import com.daigan.record.data.model.RecurrenceType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Independent acceptance regression: a live weekly window must not disappear. */
class Phase2CalendarReviewTest {
    @Test
    fun weeklyTaskRemainsActionableBetweenDailyAndWeeklyRefresh() {
        val rules = GameRefreshRules(
            zoneId = ZoneId.of("Asia/Shanghai"),
            dailyRefresh = LocalTime.of(4, 0),
            weeklyRefreshDay = DayOfWeek.MONDAY,
            weeklyRefreshTime = LocalTime.of(5, 0),
        )
        // Monday, 2026-10-19, 04:30 local: last week's window is still live.
        val now = Instant.parse("2026-10-18T20:30:00Z")
        val slot = OccurrenceRules.slotForNow(
            recurrence = RecurrenceType.WEEKLY,
            weekdays = emptySet(),
            everyNDays = null,
            periodStart = LocalDate.of(2026, 10, 12),
            periodEnd = LocalDate.of(2026, 10, 25),
            today = GameCalendar.gameDate(now, rules),
            rules = rules,
            now = now,
        )
        assertNotNull("The active weekly occurrence must remain visible until 05:00", slot)
        assertEquals("W:2026-10-12", slot!!.key)
    }

    private val rules = GameRefreshRules(
        zoneId = ZoneId.of("Asia/Shanghai"),
        dailyRefresh = LocalTime.of(4, 0),
        weeklyRefreshDay = DayOfWeek.MONDAY,
        weeklyRefreshTime = LocalTime.of(5, 0),
    )

    private fun weeklyKeyAt(instant: Instant): String? =
        OccurrenceRules.slotForNow(
            recurrence = RecurrenceType.WEEKLY,
            weekdays = emptySet(),
            everyNDays = null,
            periodStart = LocalDate.of(2026, 10, 12),
            periodEnd = LocalDate.of(2026, 10, 25),
            today = GameCalendar.gameDate(instant, rules),
            rules = rules,
            now = instant,
        )?.key

    @Test
    fun weeklyRefreshStartsExactlyAtItsConfiguredBoundary() {
        // Monday 2026-10-19 05:00 Shanghai == 2026-10-18T21:00:00Z.
        val exact = Instant.parse("2026-10-18T21:00:00Z")
        assertEquals(
            "The new weekly window opens exactly at the boundary, not after it",
            "W:2026-10-19",
            weeklyKeyAt(exact),
        )
    }

    @Test
    fun weeklyRefreshAppliesAfterItsBoundary() {
        val after = Instant.parse("2026-10-18T21:00:01Z")
        assertEquals("W:2026-10-19", weeklyKeyAt(after))
    }

    @Test
    fun weeklyRefreshHappensBeforeDailyRefreshOnMonday() {
        // Daily 04:00 == 2026-10-18T20:00:00Z. Between 04:00 and 05:00 the new game day
        // (10-19) has already begun while the previous weekly window is still open.
        assertEquals("W:2026-10-12", weeklyKeyAt(Instant.parse("2026-10-18T20:30:00Z")))
        assertEquals("W:2026-10-19", weeklyKeyAt(Instant.parse("2026-10-18T21:30:00Z")))
    }

    @Test
    fun gameDayIntersectingTwoWeeklyWindowsExposesBoth() {
        // Monday 10-19 starts at 10-18T20:00Z (daily 04:00) and the weekly refresh at
        // 05:00 falls inside it, so the game day spans both windows.
        val windows = GameCalendar.weeklyWindowsForGameDate(LocalDate.of(2026, 10, 19), rules)
        assertEquals(2, windows.size)
        assertEquals(LocalDate.of(2026, 10, 12), windows[0].startDate)
        assertEquals(LocalDate.of(2026, 10, 19), windows[1].startDate)
        assertTrue(
            "The closing window must still be open when the game day begins",
            windows[0].endExclusiveInstant.isAfter(windows[0].startInstant),
        )
        assertEquals(
            "The opening window must begin inside the same game day",
            Instant.parse("2026-10-18T21:00:00Z"),
            windows[1].startInstant,
        )
    }

    @Test
    fun gameDayWithoutWeeklyBoundaryExposesASingleWindow() {
        // Tuesday 10-20 holds no weekly refresh, so exactly one window is exposed.
        val windows = GameCalendar.weeklyWindowsForGameDate(LocalDate.of(2026, 10, 20), rules)
        assertEquals(1, windows.size)
        assertEquals(LocalDate.of(2026, 10, 19), windows[0].startDate)
    }
}
