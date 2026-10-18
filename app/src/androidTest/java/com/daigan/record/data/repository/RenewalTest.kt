package com.daigan.record.data.repository

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.hostingDraft
import com.daigan.record.testing.writeTestImage
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RenewalTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var env: OrderTestEnvironment

    @Before
    fun setUp() {
        File(context.filesDir, "attachments").deleteRecursively()
        env = OrderTestEnvironment(context)
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun renewalKeepsTheOrderAndPreservesEveryOldPeriodRecord() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.clock.set(Instant.parse("2026-10-05T02:00:00Z"))
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(
                owner.id,
                account.id,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 10),
                priceYuan = "300.00",
                tasks = listOf(TaskDraft(title = "签到"), TaskDraft(title = "副本")),
            ),
        )
        val before = env.orderRepository.observeOrderDetail(orderId).first()!!
        val oldPeriod = before.currentPeriod!!
        val oldTasks = before.tasksForPeriod(oldPeriod.id)
        env.orderRepository.setOccurrenceCompleted(oldTasks.first().id, true)
        val oldOccurrence = env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.single()
        env.attachmentRepository.addAttachment(
            taskDefinitionId = null,
            occurrenceId = oldOccurrence.id,
            sourceUri = Uri.fromFile(writeTestImage(context)),
        )

        env.clock.set(Instant.parse("2026-10-17T02:00:00Z"))
        val result = env.orderRepository.renewHosting(
            orderId = orderId,
            operationId = "renew-operation-1",
            durationDays = 30,
            priceCents = 50000,
        )
        assertTrue(!result.alreadyApplied)
        assertEquals(1, result.periodIndex)
        assertEquals(LocalDate.of(2026, 10, 17), result.startDate)
        assertEquals(LocalDate.of(2026, 11, 15), result.endDate)

        val after = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals("Renewal must not create a new order", orderId, after.order.id)
        assertEquals(1, env.orderRepository.observeOrdersForOwner(owner.id).first().size)
        assertEquals(2, after.periods.size)
        assertEquals(50000L, after.currentPeriod!!.priceCents)
        assertEquals(30000L, after.periods.first { it.id == oldPeriod.id }.priceCents)
        assertEquals(LocalDate.of(2026, 10, 1), after.periods.first { it.id == oldPeriod.id }.startDate)
        assertEquals(LocalDate.of(2026, 10, 10), after.periods.first { it.id == oldPeriod.id }.endDate)

        val newTasks = after.tasksForPeriod(after.currentPeriod!!.id)
        assertEquals(2, newTasks.size)
        assertTrue("New period task snapshots are independent", newTasks.map { it.id }.none { id -> oldTasks.any { it.id == id } })
        assertTrue(newTasks.all { it.completedAt == null })
        assertEquals(oldTasks.map { it.title }.toSet(), newTasks.map { it.title }.toSet())
        assertEquals(oldTasks.map { it.id }.toSet(), after.tasksForPeriod(oldPeriod.id).map { it.id }.toSet())
        assertEquals(
            "Old completion and screenshot survive",
            oldOccurrence.id,
            after.occurrences.single { it.taskDefinitionId == oldTasks.first().id }.id,
        )
        assertEquals(1, after.attachments.size)
    }

    @Test
    fun duplicateRenewalSubmissionUsesThePersistedOperationId() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.clock.set(Instant.parse("2026-10-17T02:00:00Z"))
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10)),
        )
        val first = env.orderRepository.renewHosting(orderId, "duplicate-op", 30, 10000)
        val second = env.orderRepository.renewHosting(orderId, "duplicate-op", 30, 10000)
        assertTrue(second.alreadyApplied)
        assertEquals(first.periodId, second.periodId)
        assertEquals(2, env.orderRepository.observeOrderDetail(orderId).first()!!.periods.size)
        assertEquals(
            "Different operation ids cannot create an overlapping period",
            1,
            runCatching {
                env.orderRepository.renewHosting(orderId, "another-op", 30, 10000)
            }.exceptionOrNull()?.let { 1 } ?: 0,
        )
    }

    @Test
    fun earlyRenewalIsRejected() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 24)),
        )
        val failure = runCatching {
            env.orderRepository.renewHosting(orderId, "early-op", 30, 10000)
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("提前续期"))
        assertEquals(1, env.orderRepository.observeOrderDetail(orderId).first()!!.periods.size)
    }

    @Test
    fun renewalBeforeDailyRefreshStartsOnTheCurrentGameDate() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(
                owner.id,
                account.id,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 16),
                tasks = listOf(TaskDraft(title = "签到")),
            ),
        )

        // 03:30 on 10-18 in Shanghai is still game day 10-17.
        env.clock.set(Instant.parse("2026-10-17T19:30:00Z"))
        val result = env.orderRepository.renewHosting(orderId, "before-refresh", 10, 10000)
        assertEquals(LocalDate.of(2026, 10, 17), result.startDate)
        assertEquals(LocalDate.of(2026, 10, 26), result.endDate)

        // No work is materialised before the new period starts; today's game day is
        // already inside the new period.
        assertEquals(0, env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.size)
        val today = env.orderRepository.observeTodayHosting().first().single()
        assertEquals(LocalDate.of(2026, 10, 17), today.today)
        assertEquals(1, today.tasks.size)
        env.orderRepository.setOccurrenceCompleted(today.tasks.single().task.id, true)
        assertEquals(
            "D:2026-10-17",
            env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.single().occurrenceKey,
        )
    }

    @Test
    fun renewalAfterDailyRefreshStartsOnTheNextGameDate() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 16)),
        )
        // 04:30 on 10-18 in Shanghai is game day 10-18.
        env.clock.set(Instant.parse("2026-10-17T20:30:00Z"))
        val result = env.orderRepository.renewHosting(orderId, "after-refresh", 10, 10000)
        assertEquals(LocalDate.of(2026, 10, 18), result.startDate)
        assertEquals(LocalDate.of(2026, 10, 27), result.endDate)
        assertEquals(LocalDate.of(2026, 10, 18), env.orderRepository.observeTodayHosting().first().single().today)
    }

    @Test
    fun renewalPreservesPreviousPeriodTasksEvenWhenNewPeriodChangesNothing() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.clock.set(Instant.parse("2026-10-17T02:00:00Z"))
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(
                owner.id,
                account.id,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 10),
                tasks = listOf(TaskDraft(title = "只做一次")),
            ),
        )
        val oldTaskId = env.orderRepository.observeOrderDetail(orderId).first()!!.tasks.single().id
        env.orderRepository.renewHosting(orderId, "preserve-op", 5, 10000)
        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        val oldTask = detail.tasks.first { it.id == oldTaskId }
        val newTask = detail.tasks.first { it.id != oldTaskId }
        assertNotEquals(oldTask.id, newTask.id)
        assertEquals(oldTask.title, newTask.title)
        assertEquals(oldTask.recurrence, newTask.recurrence)
        assertEquals(10000L, detail.currentPeriod!!.priceCents)
        assertEquals(30000L, detail.periods.first { it.periodIndex == 0 }.priceCents)
    }
}
