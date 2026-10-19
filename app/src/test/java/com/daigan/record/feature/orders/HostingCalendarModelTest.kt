package com.daigan.record.feature.orders

import com.daigan.record.data.model.GameRefreshRulesDomain
import com.daigan.record.data.model.HostingPeriod
import com.daigan.record.data.model.OrderTask
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.TaskOccurrence
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostingCalendarModelTest {
    private val start = LocalDate.of(2026, 10, 1)
    private val period = HostingPeriod(
        id = "period", orderId = "order", periodIndex = 0,
        startDate = start, endDate = LocalDate.of(2026, 10, 31), priceCents = 0,
        refresh = GameRefreshRulesDomain(ZoneId.of("Asia/Shanghai"), LocalTime.of(4, 0),
            DayOfWeek.MONDAY, LocalTime.of(5, 0)),
        createdAt = Instant.EPOCH, renewalOperationId = null,
    )

    private fun task(id: String, recurrence: RecurrenceType = RecurrenceType.DAILY,
                     weekdays: Set<DayOfWeek> = emptySet(), every: Int? = null) = OrderTask(
        id = id, orderId = "order", periodId = period.id, title = id,
        progressType = ProgressType.CHECKBOX, startValue = BigDecimal.ZERO,
        targetValue = BigDecimal.ONE, currentValue = BigDecimal.ZERO, unit = null,
        recurrence = recurrence, weekdays = weekdays, everyNDays = every,
        sortOrder = 0, completedAt = null,
    )

    private fun done(task: OrderTask, date: LocalDate, key: String = "D:$date") = TaskOccurrence(
        id = "occ-${task.id}-$key", taskDefinitionId = task.id, occurrenceKey = key,
        attributedDate = date, windowStart = date, windowEnd = date,
        completedAt = Instant.EPOCH, recordedAt = Instant.EPOCH, backfilled = date.isBefore(start.plusDays(18)),
        completionBatchId = null, value = null, createdAt = Instant.EPOCH,
    )

    @Test
    fun dayIsLitOnlyWhenEveryScheduledTaskIsComplete() {
        val day = LocalDate.of(2026, 10, 19)
        val first = task("first")
        val second = task("second")
        val partial = buildHostingCalendarMonth(period, listOf(first, second), listOf(done(first, day)),
            YearMonth.from(day), day).days.single { it.date == day }
        assertEquals(HostingCalendarDayStatus.PARTIAL, partial.status)
        val complete = buildHostingCalendarMonth(period, listOf(first, second),
            listOf(done(first, day), done(second, day)), YearMonth.from(day), day)
        assertEquals(HostingCalendarDayStatus.COMPLETE, complete.days.single { it.date == day }.status)
        assertTrue(complete.days.single { it.date == day }.isToday)
        assertEquals(1, complete.completedDayCount)
    }

    @Test
    fun recurrenceAndPeriodEdgesControlWhichDaysAreWritableAndDue() {
        val weekdays = task("weekday", RecurrenceType.SELECTED_WEEKDAYS, setOf(DayOfWeek.MONDAY))
        val everyOther = task("alternate", RecurrenceType.EVERY_N_DAYS, every = 2)
        val month = buildHostingCalendarMonth(period, listOf(weekdays, everyOther), emptyList(),
            YearMonth.of(2026, 10), LocalDate.of(2026, 10, 19))
        assertEquals(2, month.days.single { it.date == LocalDate.of(2026, 10, 5) }.tasks.size)
        assertTrue(month.days.single { it.date == LocalDate.of(2026, 10, 7) }.tasks.isNotEmpty())
        val clipped = buildHostingCalendarMonth(period.copy(endDate = LocalDate.of(2026, 10, 5)),
            listOf(weekdays, everyOther), emptyList(), YearMonth.of(2026, 10), LocalDate.of(2026, 10, 19))
        val outside = clipped.days.single { it.date == LocalDate.of(2026, 10, 6) }
        assertFalse(outside.inPeriod)
        assertEquals(HostingCalendarDayStatus.NONE, outside.status)
    }

    @Test
    fun weeklyWindowClippedAtPeriodStartHasOneCalendarOwnerDate() {
        val clippedPeriod = period.copy(startDate = LocalDate.of(2026, 10, 4), endDate = LocalDate.of(2026, 10, 10))
        val weekly = task("weekly", RecurrenceType.WEEKLY)
        val month = buildHostingCalendarMonth(clippedPeriod, listOf(weekly), emptyList(),
            YearMonth.of(2026, 10), LocalDate.of(2026, 10, 19))
        assertEquals(1, month.days.single { it.date == LocalDate.of(2026, 10, 4) }.tasks.size)
        assertEquals(1, month.days.single { it.date == LocalDate.of(2026, 10, 5) }.tasks.size)
        assertEquals("W:2026-10-05",
            month.days.single { it.date == LocalDate.of(2026, 10, 5) }.tasks.single().occurrenceKey)
    }

    @Test
    fun weeklyWindowCrossingMonthBoundaryAppearsOnlyOnItsAttributedDate() {
        val crossingPeriod = period.copy(
            startDate = LocalDate.of(2026, 10, 31),
            endDate = LocalDate.of(2026, 11, 5),
        )
        val weekly = task("weekly", RecurrenceType.WEEKLY)
        val october = buildHostingCalendarMonth(crossingPeriod, listOf(weekly), emptyList(),
            YearMonth.of(2026, 10), LocalDate.of(2026, 11, 2))
        val november = buildHostingCalendarMonth(crossingPeriod, listOf(weekly), emptyList(),
            YearMonth.of(2026, 11), LocalDate.of(2026, 11, 2))
        assertEquals(1, october.days.single { it.date == LocalDate.of(2026, 10, 31) }.tasks.size)
        assertTrue(november.days.single { it.date == LocalDate.of(2026, 11, 1) }.tasks.isEmpty())
    }
}
