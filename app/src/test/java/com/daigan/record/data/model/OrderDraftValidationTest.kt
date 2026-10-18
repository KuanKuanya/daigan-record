package com.daigan.record.data.model

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderDraftValidationTest {

    private val today = LocalDate.of(2026, 10, 17)

    private fun hosting(
        tasks: List<TaskDraft> = listOf(TaskDraft(title = "签到")),
        start: LocalDate = today,
        end: LocalDate = today.plusDays(4),
        price: String = "100.00",
    ) = HostingOrderDraft(
        ownerId = "owner",
        accountId = "account",
        startDate = start,
        endDate = end,
        priceYuan = price,
        tasks = tasks,
    )

    @Test
    fun validDraftsHaveNoErrors() {
        assertTrue(hosting().validate().isEmpty())
        assertTrue(
            ShortOrderDraft(
                ownerId = "owner",
                accountId = "account",
                priceYuan = "88",
                tasks = listOf(TaskDraft(title = "主线")),
            ).validate().isEmpty(),
        )
    }

    @Test
    fun endBeforeStartIsAnErrorWithoutThrowing() {
        val errors = hosting(start = today, end = today.minusDays(1)).validate()
        assertEquals("到期日期不能早于开始日期", errors["dates"])
    }

    @Test
    fun atLeastOneTaskIsRequired() {
        assertTrue(hosting(tasks = emptyList()).validate().containsKey("tasks"))
        assertEquals(30, ShortOrderDraft.MAX_TASKS)
    }

    @Test
    fun numericProgressNeedsATargetGreaterThanTheStart() {
        val errors = hosting(
            tasks = listOf(
                TaskDraft(
                    title = "抽数",
                    progressType = ProgressType.NUMERIC,
                    startValue = "100",
                    targetValue = "100",
                ),
            ),
        ).validate()
        assertEquals("目标值需大于起始值", errors["task.0.target"])
    }

    @Test
    fun selectedWeekdaysNeedAtLeastOneDayAndEveryNNeedsAValidInterval() {
        val weekdayErrors = hosting(
            tasks = listOf(
                TaskDraft(
                    title = "周常",
                    recurrence = RecurrenceType.SELECTED_WEEKDAYS,
                    weekdays = emptySet(),
                ),
            ),
        ).validate()
        assertTrue(weekdayErrors.containsKey("task.0.weekdays"))

        val intervalErrors = hosting(
            tasks = listOf(
                TaskDraft(
                    title = "间隔",
                    recurrence = RecurrenceType.EVERY_N_DAYS,
                    everyNDays = "0",
                ),
            ),
        ).validate()
        assertTrue(intervalErrors.containsKey("task.0.interval"))
    }

    @Test
    fun recurrenceWeekdaysAreStoredAsIsoNumbers() {
        val encoded = com.daigan.record.data.local.encodeWeekdays(
            setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
        )
        assertEquals("1,5", encoded)
    }
}
