package com.kuankuan.daigan.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kuankuan.daigan.core.time.HostingStatus
import com.kuankuan.daigan.data.model.ProgressType
import com.kuankuan.daigan.data.model.RecurrenceType
import com.kuankuan.daigan.data.model.TaskDraft
import com.kuankuan.daigan.testing.OrderTestEnvironment
import com.kuankuan.daigan.testing.hostingDraft
import com.kuankuan.daigan.testing.hostingSnapshots
import com.kuankuan.daigan.testing.numericTask
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostingOccurrenceTest {

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

    private suspend fun createHosting(
        ownerId: String,
        accountId: String,
        start: LocalDate,
        end: LocalDate,
        tasks: List<TaskDraft>,
    ): String = env.orderRepository.createHostingOrder(
        hostingDraft(ownerId, accountId, start, end, tasks = tasks),
    )

    @Test
    fun dailyCompletionIsPersistedAndRepeatedTapsDoNotDuplicate() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = createHosting(
            owner.id,
            account.id,
            LocalDate.of(2026, 10, 15),
            LocalDate.of(2026, 10, 20),
            listOf(TaskDraft(title = "签到"), TaskDraft(title = "日常")),
        )
        var today = env.orderRepository.observeTodayHosting().first().single()
        assertEquals(2, today.tasks.size)
        assertNull("Reads do not materialise incomplete rows", today.tasks.first().occurrenceId)

        val first = today.tasks.first().task
        env.orderRepository.setOccurrenceCompleted(first.id, true)
        env.orderRepository.setOccurrenceCompleted(first.id, true)
        var detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(1, detail.occurrences.size)
        assertEquals("D:2026-10-17", detail.occurrences.single().occurrenceKey)
        assertNotNull(detail.occurrences.single().completedAt)

        today = env.orderRepository.observeTodayHosting().first().single()
        env.orderRepository.setOccurrenceCompleted(today.tasks[1].task.id, true)
        detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(2, detail.occurrences.size)

        val again = env.orderRepository.completeAllHosting(
            "repeat-after-complete",
            env.hostingSnapshots(),
        )
        assertEquals(0, again.completedCount)
        assertEquals(2, env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.size)
    }

    @Test
    fun wholeOrderCompletionIsRejectedAfterTheDailyRefreshBoundaryCrossed() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = createHosting(
            owner.id,
            account.id,
            LocalDate.of(2026, 10, 15),
            LocalDate.of(2026, 10, 20),
            listOf(TaskDraft(title = "签到")),
        )
        val displayed = env.hostingSnapshots().single()
        assertEquals("D:2026-10-17", displayed.occurrenceKeys.values.single())

        // The screen keeps showing 10-17, but the clock crosses the 04:00 daily refresh.
        env.clock.set(Instant.parse("2026-10-17T21:00:00Z"))
        assertStale { env.orderRepository.completeHostingOrder(orderId, "whole-order-stale", displayed) }
        assertEquals(
            "A rejected batch must not write any occurrence",
            0,
            env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.size,
        )

        // Refreshing the displayed identity matches the new game day again.
        val fresh = env.hostingSnapshots().single()
        assertEquals("D:2026-10-18", fresh.occurrenceKeys.values.single())
        val applied = env.orderRepository.completeHostingOrder(orderId, "whole-order-fresh", fresh)
        assertEquals(1, applied.completedCount)
    }

    @Test
    fun completeAllHostingIsRejectedAfterTheWeeklyWindowCrossedOnTheSameGameDay() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = createHosting(
            owner.id,
            account.id,
            LocalDate.of(2026, 10, 15),
            LocalDate.of(2026, 10, 20),
            listOf(TaskDraft(title = "周常", recurrence = RecurrenceType.WEEKLY)),
        )
        // Monday 2026-10-19 04:30 Shanghai: the 04:00 daily refresh already moved the game
        // date to Monday, but the 05:00 weekly refresh has not happened, so the still-open
        // window is the previous one and the screen shows W:2026-10-12.
        env.clock.set(Instant.parse("2026-10-18T20:30:00Z"))
        val displayed = env.hostingSnapshots().single()
        assertEquals("W:2026-10-12", displayed.occurrenceKeys.values.single())

        // The clock crosses 05:00 (2026-10-18T21:00Z) on the same game day, opening the
        // new weekly window without leaving the 2026-10-19 game date.
        env.clock.set(Instant.parse("2026-10-18T21:00:00Z"))
        val fresh = env.hostingSnapshots().single()
        assertEquals("W:2026-10-19", fresh.occurrenceKeys.values.single())

        assertStale { env.orderRepository.completeAllHosting("all-hosting-weekly-stale", listOf(displayed)) }
        assertEquals(
            "A rejected bulk batch must not write any occurrence",
            0,
            env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.size,
        )

        val applied = env.orderRepository.completeAllHosting("all-hosting-weekly-fresh", listOf(fresh))
        assertEquals(1, applied.completedCount)
        assertEquals(
            "W:2026-10-19",
            env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.single().occurrenceKey,
        )
    }

    @Test
    fun wholeOrderCompletionKeepsOperationIdIdempotencyAcrossAStaleRetry() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = createHosting(
            owner.id,
            account.id,
            LocalDate.of(2026, 10, 15),
            LocalDate.of(2026, 10, 20),
            listOf(TaskDraft(title = "签到")),
        )
        val displayed = env.hostingSnapshots().single()
        val first = env.orderRepository.completeHostingOrder(orderId, "idem-op", displayed)
        assertEquals(1, first.completedCount)

        // Crossing the boundary and replaying the same operation id must return the
        // original batch instead of failing or writing a second batch.
        env.clock.set(Instant.parse("2026-10-17T21:00:00Z"))
        val replay = env.orderRepository.completeHostingOrder(orderId, "idem-op", displayed)
        assertTrue(replay.alreadyApplied)
        assertEquals(first.batchId, replay.batchId)
        assertEquals(1, env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.size)
    }

    private suspend fun assertStale(block: suspend () -> Unit) {
        try {
            block()
            fail("预期抛出 StaleOccurrenceException")
        } catch (_: StaleOccurrenceException) {
            // expected: the displayed work is stale and the whole batch is rejected
        }
    }

    @Test
    fun dailyRefreshBoundaryMovesAttributionToTheNextGameDate() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = createHosting(
            owner.id,
            account.id,
            LocalDate.of(2026, 10, 15),
            LocalDate.of(2026, 10, 20),
            listOf(TaskDraft(title = "签到")),
        )

        // 03:59 on 10-18 in Shanghai is still game day 10-17.
        env.clock.set(Instant.parse("2026-10-17T19:59:00Z"))
        var today = env.orderRepository.observeTodayHosting().first().single()
        assertEquals(LocalDate.of(2026, 10, 17), today.today)
        env.orderRepository.setOccurrenceCompleted(today.tasks.single().task.id, true)

        // 04:00 starts 10-18; the same task is now actionable again.
        env.clock.set(Instant.parse("2026-10-17T20:00:00Z"))
        today = env.orderRepository.observeTodayHosting().first().single()
        assertEquals(LocalDate.of(2026, 10, 18), today.today)
        assertTrue(!today.tasks.single().completed)
        env.orderRepository.setOccurrenceCompleted(today.tasks.single().task.id, true)

        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(2, detail.occurrences.size)
        assertEquals(
            setOf("D:2026-10-17", "D:2026-10-18"),
            detail.occurrences.map { it.occurrenceKey }.toSet(),
        )
    }

    @Test
    fun weeklyTaskHasOneOccurrenceForTheWholeWindow() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.clock.set(Instant.parse("2026-10-20T02:00:00Z")) // Tuesday 10:00 Shanghai
        val orderId = createHosting(
            owner.id,
            account.id,
            LocalDate.of(2026, 10, 12),
            LocalDate.of(2026, 10, 25),
            listOf(TaskDraft(title = "周常", recurrence = RecurrenceType.WEEKLY)),
        )

        val today = env.orderRepository.observeTodayHosting().first().single()
        assertEquals(1, today.tasks.size)
        assertEquals("10.19–10.25", today.tasks.single().windowLabel)
        env.orderRepository.setOccurrenceCompleted(today.tasks.single().task.id, true)

        // Backfilling another day in the same game week refers to the same occurrence.
        env.orderRepository.setHistoricalCompletion(
            orderId = orderId,
            periodId = today.period.id,
            taskId = today.tasks.single().task.id,
            date = LocalDate.of(2026, 10, 19),
            completed = true,
        )
        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(1, detail.occurrences.size)
        val occurrence = detail.occurrences.single()
        assertEquals("W:2026-10-19", occurrence.occurrenceKey)
        assertEquals(LocalDate.of(2026, 10, 19), occurrence.attributedDate)
        assertTrue("Historical correction is marked", occurrence.backfilled)
        assertEquals(LocalDate.of(2026, 10, 19), occurrence.windowStart)
        assertEquals(LocalDate.of(2026, 10, 25), occurrence.windowEnd)

        // The current window still shows the same completed weekly occurrence.
        val nextDay = env.orderRepository.observeTodayHosting().first().single()
        assertTrue(nextDay.tasks.single().completed)
    }

    @Test
    fun selectedWeekdaysOnlyProduceWorkOnThoseDays() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = createHosting(
            owner.id,
            account.id,
            LocalDate.of(2026, 10, 12),
            LocalDate.of(2026, 10, 25),
            listOf(
                TaskDraft(
                    title = "周一三五",
                    recurrence = RecurrenceType.SELECTED_WEEKDAYS,
                    weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                ),
            ),
        )
        env.clock.set(Instant.parse("2026-10-19T02:00:00Z")) // Monday
        assertEquals(1, env.orderRepository.observeTodayHosting().first().single().tasks.size)

        env.clock.set(Instant.parse("2026-10-20T02:00:00Z")) // Tuesday
        assertEquals(0, env.orderRepository.observeTodayHosting().first().single().tasks.size)

        env.clock.set(Instant.parse("2026-10-21T02:00:00Z")) // Wednesday
        assertEquals(1, env.orderRepository.observeTodayHosting().first().single().tasks.size)
        assertEquals(0, env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.size)
    }

    @Test
    fun everyNDaysIsAnchoredToThePeriodStartDate() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        createHosting(
            owner.id,
            account.id,
            LocalDate.of(2026, 10, 12),
            LocalDate.of(2026, 10, 25),
            listOf(
                TaskDraft(
                    title = "每三天",
                    recurrence = RecurrenceType.EVERY_N_DAYS,
                    everyNDays = "3",
                ),
            ),
        )
        env.clock.set(Instant.parse("2026-10-13T02:00:00Z")) // day 1
        assertEquals(0, env.orderRepository.observeTodayHosting().first().single().tasks.size)
        env.clock.set(Instant.parse("2026-10-15T02:00:00Z")) // day 3
        assertEquals(1, env.orderRepository.observeTodayHosting().first().single().tasks.size)
        env.clock.set(Instant.parse("2026-10-18T02:00:00Z")) // day 6
        assertEquals(1, env.orderRepository.observeTodayHosting().first().single().tasks.size)
    }

    @Test
    fun expiredPeriodStopsNewWorkButKeepsHistory() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.clock.set(Instant.parse("2026-10-12T02:00:00Z"))
        val orderId = createHosting(
            owner.id,
            account.id,
            LocalDate.of(2026, 10, 10),
            LocalDate.of(2026, 10, 14),
            listOf(TaskDraft(title = "签到")),
        )
        val task = env.orderRepository.observeTodayHosting().first().single().tasks.single().task
        env.orderRepository.setOccurrenceCompleted(task.id, true)

        env.clock.set(Instant.parse("2026-10-20T02:00:00Z"))
        val expired = env.orderRepository.observeTodayHosting().first().single()
        assertEquals(HostingStatus.EXPIRED, expired.status)
        assertTrue(expired.tasks.isEmpty())
        val failure = runCatching { env.orderRepository.setOccurrenceCompleted(task.id, true) }
            .exceptionOrNull()
        assertNotNull("Expired periods cannot generate new work", failure)

        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(1, detail.occurrences.size)
        assertEquals(1, detail.historyEntries().size)
        assertNotNull(detail.occurrences.single().completedAt)
    }

    @Test
    fun futureStartProducesNoWork() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        createHosting(
            owner.id,
            account.id,
            LocalDate.of(2026, 10, 20),
            LocalDate.of(2026, 10, 30),
            listOf(TaskDraft(title = "签到")),
        )
        val state = env.orderRepository.observeTodayHosting().first().single()
        assertEquals(HostingStatus.NOT_STARTED, state.status)
        assertTrue(state.tasks.isEmpty())
    }

    @Test
    fun futureDatedBackfillIsRejectedBeforeAnyDatabaseWrite() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = createHosting(
            owner.id,
            account.id,
            LocalDate.of(2026, 10, 12),
            LocalDate.of(2026, 10, 25),
            listOf(TaskDraft(title = "签到")),
        )
        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        val period = detail.currentPeriod!!
        val task = detail.tasksForPeriod(period.id).single()

        // 2026-10-17 is the current game day; 2026-10-18 has not started yet.
        val failure = runCatching {
            env.orderRepository.setHistoricalCompletion(
                orderId,
                period.id,
                task.id,
                LocalDate.of(2026, 10, 18),
                true,
            )
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(
            "A rejected future correction must not create any occurrence",
            env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.isEmpty(),
        )
    }

    @Test
    fun notYetStartedWeeklyWindowIsRejectedBeforeAnyDatabaseWrite() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = createHosting(
            owner.id,
            account.id,
            LocalDate.of(2026, 10, 12),
            LocalDate.of(2026, 10, 25),
            listOf(TaskDraft(title = "每周一", recurrence = RecurrenceType.WEEKLY)),
        )
        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        val period = detail.currentPeriod!!
        val task = detail.tasksForPeriod(period.id).single()

        // The next weekly window starts on 2026-10-19, which has not started yet.
        val failure = runCatching {
            env.orderRepository.setHistoricalCompletion(
                orderId,
                period.id,
                task.id,
                LocalDate.of(2026, 10, 19),
                true,
                weeklyWindowStart = LocalDate.of(2026, 10, 19),
            )
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(
            "A rejected weekly window must not create any occurrence",
            env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.isEmpty(),
        )
    }

    @Test
    fun notYetStartedHostingPeriodRemainsReadOnly() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = createHosting(
            owner.id,
            account.id,
            LocalDate.of(2026, 10, 20),
            LocalDate.of(2026, 10, 30),
            listOf(TaskDraft(title = "签到")),
        )
        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        val period = detail.currentPeriod!!
        val task = detail.tasksForPeriod(period.id).single()

        val failure = runCatching {
            env.orderRepository.setHistoricalCompletion(
                orderId,
                period.id,
                task.id,
                LocalDate.of(2026, 10, 17),
                true,
            )
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(
            "A period that has not started must stay read-only",
            env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.isEmpty(),
        )
    }

    @Test
    fun backfillSavesAttributedGameDateAndActualRecordingTime() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.clock.set(Instant.parse("2026-10-20T02:00:00Z"))
        val orderId = createHosting(
            owner.id,
            account.id,
            LocalDate.of(2026, 10, 12),
            LocalDate.of(2026, 10, 25),
            listOf(TaskDraft(title = "签到")),
        )
        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        val period = detail.currentPeriod!!
        val task = detail.tasksForPeriod(period.id).single()

        env.orderRepository.setHistoricalCompletion(
            orderId = orderId,
            periodId = period.id,
            taskId = task.id,
            date = LocalDate.of(2026, 10, 17),
            completed = true,
        )
        val occurrence = env.orderRepository.observeOrderDetail(orderId).first()!!
            .occurrences.single()
        assertEquals(LocalDate.of(2026, 10, 17), occurrence.attributedDate)
        assertEquals(env.clock.now(), occurrence.completedAt)
        assertEquals(env.clock.now(), occurrence.recordedAt)
        assertTrue(occurrence.backfilled)
        assertEquals("D:2026-10-17", occurrence.occurrenceKey)

        env.clock.set(Instant.parse("2026-10-21T02:00:00Z"))
        env.orderRepository.setHistoricalCompletion(
            orderId = orderId,
            periodId = period.id,
            taskId = task.id,
            date = LocalDate.of(2026, 10, 17),
            completed = false,
        )
        val cleared = env.orderRepository.observeOrderDetail(orderId).first()!!
            .occurrences.single()
        assertNull(cleared.completedAt)
        assertTrue(cleared.backfilled)
        assertEquals(LocalDate.of(2026, 10, 17), cleared.attributedDate)
    }

    @Test
    fun backfillOutsideThePeriodOrOffScheduleIsRejected() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = createHosting(
            owner.id,
            account.id,
            LocalDate.of(2026, 10, 12),
            LocalDate.of(2026, 10, 25),
            listOf(
                TaskDraft(
                    title = "每周一",
                    recurrence = RecurrenceType.SELECTED_WEEKDAYS,
                    weekdays = setOf(DayOfWeek.MONDAY),
                ),
            ),
        )
        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        val period = detail.currentPeriod!!
        val task = detail.tasksForPeriod(period.id).single()

        assertNotNull(
            runCatching {
                env.orderRepository.setHistoricalCompletion(
                    orderId,
                    period.id,
                    task.id,
                    LocalDate.of(2026, 10, 11),
                    true,
                )
            }.exceptionOrNull(),
        )
        assertNotNull(
            runCatching {
                env.orderRepository.setHistoricalCompletion(
                    orderId,
                    period.id,
                    task.id,
                    LocalDate.of(2026, 10, 13), // Tuesday
                    true,
                )
            }.exceptionOrNull(),
        )
        assertTrue(env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.isEmpty())
    }

    @Test
    fun numericHostingProgressCompletesOnlyAtTheTarget() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = createHosting(
            owner.id,
            account.id,
            LocalDate.of(2026, 10, 15),
            LocalDate.of(2026, 10, 20),
            listOf(numericTask()),
        )
        val task = env.orderRepository.observeTodayHosting().first().single().tasks.single().task
        env.orderRepository.updateOccurrenceProgress(task.id, java.math.BigDecimal("40"))
        var occurrence = env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.single()
        assertNull(occurrence.completedAt)
        assertEquals(ProgressType.NUMERIC, task.progressType)

        env.orderRepository.updateOccurrenceProgress(task.id, java.math.BigDecimal("100"))
        occurrence = env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.single()
        assertNotNull(occurrence.completedAt)
        assertEquals(0, java.math.BigDecimal("100").compareTo(occurrence.value))
    }
}
