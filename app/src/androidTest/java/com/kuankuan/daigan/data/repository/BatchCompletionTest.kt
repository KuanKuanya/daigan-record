package com.kuankuan.daigan.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kuankuan.daigan.data.model.TaskDraft
import com.kuankuan.daigan.testing.OrderTestEnvironment
import com.kuankuan.daigan.testing.hostingDraft
import com.kuankuan.daigan.testing.hostingSnapshot
import com.kuankuan.daigan.testing.hostingSnapshots
import com.kuankuan.daigan.testing.shortDraft
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchCompletionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var env: OrderTestEnvironment
    private val today = LocalDate.of(2026, 10, 17)

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
    fun bulkCompletionUndoRestoresOnlyTheBatchChanges() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val first = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, today.minusDays(3), today.plusDays(5), title = "单A"),
        )
        val second = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, today.minusDays(3), today.plusDays(5), title = "单B"),
        )
        val firstTask = env.orderRepository.observeOrderDetail(first).first()!!.tasks.single()
        // Pre-existing completion must survive a later undo.
        env.orderRepository.setOccurrenceCompleted(firstTask.id, true)

        val batch = env.orderRepository.completeAllHosting("batch-all-1", env.hostingSnapshots())
        assertEquals(1, batch.completedCount)
        val secondBeforeUndo = env.orderRepository.observeOrderDetail(second).first()!!
        assertEquals(1, secondBeforeUndo.occurrences.count { it.completedAt != null })

        val noOp = env.orderRepository.completeAllHosting("repeat-${batch.batchId}", env.hostingSnapshots())
        assertEquals(0, noOp.completedCount)
        val undo = env.orderRepository.undoBatch(batch.batchId)
        assertEquals(1, undo.restoredCount)
        assertTrue(undo.conflicts.isEmpty())

        val firstAfter = env.orderRepository.observeOrderDetail(first).first()!!
        val secondAfter = env.orderRepository.observeOrderDetail(second).first()!!
        assertTrue("Earlier individual work stays completed", firstAfter.occurrences.single().completedAt != null)
        assertNull("Batch completion is undone", secondAfter.occurrences.single().completedAt)
    }

    @Test
    fun undoKeepsSubsequentEditsAndReportsConflicts() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val first = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, today.minusDays(3), today.plusDays(5), title = "单A"),
        )
        val second = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, today.minusDays(3), today.plusDays(5), title = "单B"),
        )
        val batch = env.orderRepository.completeAllHosting("batch-all-2", env.hostingSnapshots())
        assertEquals(2, batch.completedCount)

        val firstTask = env.orderRepository.observeOrderDetail(first).first()!!.tasks.single()
        // Independent edit after the batch: uncheck then check again.
        env.orderRepository.setOccurrenceCompleted(firstTask.id, false)
        env.orderRepository.setOccurrenceCompleted(firstTask.id, true)

        val undo = env.orderRepository.undoBatch(batch.batchId)
        assertEquals(1, undo.restoredCount)
        assertEquals(1, undo.conflicts.size)

        val firstAfter = env.orderRepository.observeOrderDetail(first).first()!!
        val secondAfter = env.orderRepository.observeOrderDetail(second).first()!!
        assertNotNull("Later independent edit is preserved", firstAfter.occurrences.single().completedAt)
        assertNull(secondAfter.occurrences.single().completedAt)
    }

    @Test
    fun repeatedOperationIdDoesNotCreateAnotherBatch() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, today.minusDays(3), today.plusDays(5)),
        )
        val first = env.orderRepository.completeHostingOrder(orderId, "same-operation", env.hostingSnapshot(orderId))
        val second = env.orderRepository.completeHostingOrder(orderId, "same-operation", env.hostingSnapshot(orderId))
        assertEquals(first.batchId, second.batchId)
        assertTrue(second.alreadyApplied)
        assertEquals(1, env.database.completionBatchDao().count())

        val again = env.orderRepository.completeHostingOrder(orderId, "different-operation", env.hostingSnapshot(orderId))
        assertEquals(0, again.completedCount)
        assertEquals(1, env.orderRepository.undoBatch(first.batchId).restoredCount)
        assertNull(env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.single().completedAt)
    }

    @Test
    fun wholeOrderCompletionLeavesOtherOrdersAlone() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val first = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, today.minusDays(3), today.plusDays(5), title = "单A"),
        )
        val second = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, today.minusDays(3), today.plusDays(5), title = "单B"),
        )
        val result = env.orderRepository.completeHostingOrder(first, "single-order-batch", env.hostingSnapshot(first))
        assertEquals(1, result.completedCount)
        assertTrue(result.batchId.isNotBlank())

        val firstDetail = env.orderRepository.observeOrderDetail(first).first()!!
        val secondDetail = env.orderRepository.observeOrderDetail(second).first()!!
        assertTrue(firstDetail.occurrences.all { it.completedAt != null })
        assertTrue(secondDetail.occurrences.isEmpty())
        assertEquals(first, env.database.completionBatchDao().findById(result.batchId)!!.targetId)
    }

    @Test
    fun bulkCompletionNeverBackfillsHistoricalMisses() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, today.minusDays(3), today.plusDays(5)),
        )
        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        val period = detail.currentPeriod!!
        val task = detail.tasksForPeriod(period.id).single()
        env.orderRepository.setHistoricalCompletion(
            orderId = orderId,
            periodId = period.id,
            taskId = task.id,
            date = today.minusDays(1),
            completed = true,
        )
        val yesterday = env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.single()

        val batch = env.orderRepository.completeAllHosting("today-only", env.hostingSnapshots())
        val after = env.orderRepository.observeOrderDetail(orderId).first()!!
        val historical = after.occurrences.first { it.occurrenceKey == yesterday.occurrenceKey }
        assertTrue(historical.backfilled)
        assertNull("Historical completion is not part of today's batch", historical.completionBatchId)
        assertEquals(1, batch.completedCount)

        val undo = env.orderRepository.undoBatch(batch.batchId)
        assertEquals(1, undo.restoredCount)
        val afterUndo = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertTrue(
            "Backfilled history is untouched by undo",
            afterUndo.occurrences.first { it.occurrenceKey == yesterday.occurrenceKey }.completedAt != null,
        )
    }

    @Test
    fun shortOrderWholeCompletionCanBeUndoneWithoutLosingEarlierWork() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createShortOrder(
            shortDraft(
                owner.id,
                account.id,
                tasks = listOf(TaskDraft(title = "任务A"), TaskDraft(title = "任务B")),
            ),
        )
        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        val firstTask = detail.tasks.first()
        env.orderRepository.setShortTaskCompleted(firstTask.id, true)

        val result = env.orderRepository.completeShortOrder(orderId, "short-batch")
        assertEquals(1, result.completedCount)
        var after = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertNotNull(after.order.completedAt)
        assertTrue(after.tasks.all { it.completed })

        val noOp = env.orderRepository.completeShortOrder(orderId, "short-repeat")
        assertEquals(0, noOp.completedCount)
        val undo = env.orderRepository.undoBatch(result.batchId)
        assertEquals(1, undo.restoredCount)
        after = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertNull(after.order.completedAt)
        assertNotNull(
            "The task completed before the batch is preserved",
            after.tasks.first { it.id == firstTask.id }.completedAt,
        )
        assertNull(after.tasks.first { it.id != firstTask.id }.completedAt)
    }

    @Test
    fun bulkCompletionIsRejectedForArchivedOrMissingRecordsWithoutWritingABatch() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, today.minusDays(3), today.plusDays(5)),
        )
        // The active period guard rejects this archive, so the order stays writable.
        assertNotNull(runCatching { env.ownerRepository.archiveOwner(owner.id) }.exceptionOrNull())
        assertNull(env.database.ownerDao().findById(owner.id)!!.archivedAt)

        val failure = runCatching {
            env.orderRepository.completeHostingOrder(
                "missing-order",
                "missing-batch",
                env.hostingSnapshot(orderId),
            )
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(0, env.database.completionBatchDao().count())
        assertEquals(0, env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.size)
    }
}
