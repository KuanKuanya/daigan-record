package com.kuankuan.daigan.notifications

import com.kuankuan.daigan.core.time.GameRefreshRules
import com.kuankuan.daigan.data.model.OrderType
import com.kuankuan.daigan.data.model.RecurrenceType
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class ReminderRulesTest {
    private val rules = GameRefreshRules(ZoneId.of("Asia/Shanghai"), LocalTime.of(4, 0), DayOfWeek.MONDAY, LocalTime.of(5, 0))
    private val day = LocalDate.of(2026, 10, 18)
    private fun order(type: OrderType = OrderType.HOSTING) = ReminderOrder(
        "order", type, "任务", "老板", "游戏", false, false, day, rules,
        ReminderPeriod(day.minusDays(2), day, rules),
        listOf(ReminderTask("task", "签到", RecurrenceType.DAILY, emptySet(), null, false)), emptyList(),
    )
    private fun messages(order: ReminderOrder, at: String) = ReminderEvaluator.messages(ReminderSnapshot(listOf(order)), Instant.parse(at))

    @Test fun `monthly reminders use fixed date and completion key`() {
        val monthly = order().copy(tasks = listOf(order().tasks.single().copy(recurrence = RecurrenceType.MONTHLY, monthDay = 18)))
        val now = Instant.parse("2026-10-18T12:00:00Z")
        assertEquals(setOf("task"), ReminderEvaluator.dueHostingTaskIds(monthly, now))
        assertTrue(ReminderEvaluator.dueHostingTaskIds(monthly.copy(occurrences = listOf(ReminderOccurrence("task", "M:2026-10-18", true))), now).isEmpty())
        assertTrue(ReminderEvaluator.dueHostingTaskIds(monthly.copy(tasks = listOf(monthly.tasks.single().copy(monthDay = 31))), now).isEmpty())
    }
    @Test fun `deadline uses order refresh rather than device midnight`() {
        val short = order(OrderType.SHORT_TASK)
        assertFalse(messages(short, "2026-10-17T19:59:59Z").containsKey(ReminderCategory.SHORT_DEADLINE))
        assertTrue(messages(short, "2026-10-17T20:00:00Z").containsKey(ReminderCategory.SHORT_DEADLINE))
        assertFalse(messages(short.copy(completed = true), "2026-10-18T12:00:00Z").containsKey(ReminderCategory.SHORT_DEADLINE))
    }
    @Test fun `hosting final game day inclusive but expired period stops all work`() {
        assertTrue(messages(order(), "2026-10-18T19:59:59Z").containsKey(ReminderCategory.HOSTING_EXPIRY))
        assertTrue(messages(order(), "2026-10-18T20:00:00Z").isEmpty())
        assertTrue(messages(order().copy(archived = true), "2026-10-18T12:00:00Z").isEmpty())
    }
    @Test fun `historical completion cannot silence current game day and undo restores eligibility`() {
        val old = ReminderOccurrence("task", "D:2026-10-17", true)
        val current = ReminderOccurrence("task", "D:2026-10-18", true)
        val now = Instant.parse("2026-10-18T12:00:00Z")
        assertEquals(setOf("task"), ReminderEvaluator.dueHostingTaskIds(order().copy(occurrences = listOf(old)), now))
        assertTrue(ReminderEvaluator.dueHostingTaskIds(order().copy(occurrences = listOf(current)), now).isEmpty())
        assertEquals(setOf("task"), ReminderEvaluator.dueHostingTaskIds(order().copy(occurrences = listOf(current.copy(completed = false))), now))
    }
    @Test fun `custom cycles anchor to period start and selected weekdays apply`() {
        val now = Instant.parse("2026-10-18T12:00:00Z")
        val task = order().tasks.single()
        assertEquals(setOf("task"), ReminderEvaluator.dueHostingTaskIds(order().copy(tasks = listOf(task.copy(recurrence = RecurrenceType.EVERY_N_DAYS, everyNDays = 2))), now))
        assertTrue(ReminderEvaluator.dueHostingTaskIds(order().copy(tasks = listOf(task.copy(recurrence = RecurrenceType.EVERY_N_DAYS, everyNDays = 3))), now).isEmpty())
        assertTrue(ReminderEvaluator.dueHostingTaskIds(order().copy(tasks = listOf(task.copy(recurrence = RecurrenceType.SELECTED_WEEKDAYS, weekdays = setOf(DayOfWeek.MONDAY)))), now).isEmpty())
    }
    @Test fun `weekly refresh uses independent window key inside same game day`() {
        val weekly = order().copy(period = ReminderPeriod(day.minusDays(2), day.plusDays(3), rules), tasks = listOf(order().tasks.single().copy(recurrence = RecurrenceType.WEEKLY)), occurrences = listOf(ReminderOccurrence("task", "W:2026-10-12", true)))
        assertTrue(ReminderEvaluator.dueHostingTaskIds(weekly, Instant.parse("2026-10-18T20:59:59Z")).isEmpty())
        assertEquals(setOf("task"), ReminderEvaluator.dueHostingTaskIds(weekly, Instant.parse("2026-10-18T21:00:00Z")))
    }
    @Test fun `clock skips DST gaps chooses first overlap and next day after exact minute`() {
        val zone = ZoneId.of("America/New_York")
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), ReminderTiming.nextTrigger(Instant.parse("2026-03-08T05:00:00Z"), 150, zone))
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), ReminderTiming.nextTrigger(Instant.parse("2026-11-01T04:00:00Z"), 90, zone))
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), ReminderTiming.nextTrigger(Instant.parse("2026-11-01T05:30:00Z"), 90, zone))
    }
    @Test fun `independent toggles times and safe aggregate navigation`() {
        val settings = ReminderSettings().withEnabled(ReminderCategory.SHORT_DEADLINE, true).withMinutes(ReminderCategory.HOSTING_EXPIRY, 75)
        assertTrue(settings.shortDeadlineEnabled)
        assertFalse(settings.hostingExpiryEnabled)
        assertEquals(75, settings.hostingExpiryMinutes)
        val orders = listOf(order(OrderType.SHORT_TASK), order(OrderType.SHORT_TASK).copy(orderId = "other"))
        assertNull(ReminderEvaluator.messages(ReminderSnapshot(orders), Instant.parse("2026-10-18T12:00:00Z"))[ReminderCategory.SHORT_DEADLINE]!!.orderId)
    }
}
