package com.daigan.record.review

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.daigan.record.core.time.HostingStatus
import com.daigan.record.data.model.Attachment
import com.daigan.record.data.model.BulkCompletionResult
import com.daigan.record.data.model.GameRefreshRulesDomain
import com.daigan.record.data.model.HostingPeriod
import com.daigan.record.data.model.Order
import com.daigan.record.data.model.OrderDetail
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.TodayHostingOrder
import com.daigan.record.data.model.UndoResult
import com.daigan.record.data.repository.AttachmentRepository
import com.daigan.record.data.repository.DisplayedHostingSnapshot
import com.daigan.record.data.repository.OrderRepository
import com.daigan.record.feature.orders.OrderDetailViewModel
import com.daigan.record.feature.today.TodayViewModel
import com.daigan.record.testing.FakeOrderRepository
import com.daigan.record.testing.FixedTimeProvider
import com.daigan.record.testing.MainDispatcherRule
import com.daigan.record.testing.account
import com.daigan.record.testing.fixedInstant
import com.daigan.record.testing.owner
import com.daigan.record.ui.navigation.Routes
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** A queued second tap must not replace the useful undo token with an empty batch. */
@OptIn(ExperimentalCoroutinesApi::class)
class Phase2UndoReviewTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun emptyRepeatedCompletionPreservesTheLastEffectiveUndoBatch() = runTest {
        var calls = 0
        val repository = object : OrderRepository by FakeOrderRepository() {
            override suspend fun completeAllHosting(
                operationId: String,
                displayed: List<DisplayedHostingSnapshot>,
            ): BulkCompletionResult {
                calls += 1
                return if (calls == 1) {
                    BulkCompletionResult("effective-batch", 2, 0, false)
                } else {
                    BulkCompletionResult("empty-batch", 0, 0, false)
                }
            }
        }
        val viewModel = TodayViewModel(FixedTimeProvider(), repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        viewModel.completeAllHosting()
        runCurrent()
        assertEquals("effective-batch", viewModel.uiState.value.lastBatchId)
        viewModel.completeAllHosting()
        runCurrent()
        assertEquals(
            "A no-op must not discard the undo token for the preceding actual change",
            "effective-batch",
            viewModel.uiState.value.lastBatchId,
        )
    }

    @Test
    fun overlappingCompletionIsIgnoredUntilTheFirstRequestFinishes() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val repository = object : OrderRepository by FakeOrderRepository() {
            override suspend fun completeAllHosting(
                operationId: String,
                displayed: List<DisplayedHostingSnapshot>,
            ): BulkCompletionResult {
                calls += 1
                release.await()
                return BulkCompletionResult("effective-batch", 1, 0, false)
            }
        }
        val vm = TodayViewModel(FixedTimeProvider(), repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        vm.completeAllHosting()
        vm.completeAllHosting()
        runCurrent()
        assertEquals(1, calls)
        release.complete(Unit)
        runCurrent()
        assertEquals("effective-batch", vm.uiState.value.lastBatchId)
        vm.completeAllHosting()
        runCurrent()
        assertEquals("The gate must reopen after completion", 2, calls)
    }

    @Test
    fun failedCompletionPreservesUndoAndAllowsRetry() = runTest {
        var calls = 0
        val repository = object : OrderRepository by FakeOrderRepository() {
            override suspend fun completeAllHosting(
                operationId: String,
                displayed: List<DisplayedHostingSnapshot>,
            ): BulkCompletionResult {
                calls += 1
                if (calls == 2) error("write failed")
                return BulkCompletionResult("batch-$calls", 1, 0, false)
            }
        }
        val vm = TodayViewModel(FixedTimeProvider(), repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        vm.completeAllHosting()
        runCurrent()
        vm.completeAllHosting()
        runCurrent()
        assertEquals("batch-1", vm.uiState.value.lastBatchId)
        vm.completeAllHosting()
        runCurrent()
        assertEquals("batch-3", vm.uiState.value.lastBatchId)
    }

    @Test fun todayWholeOrderKeepsEffectiveUndoAfterNoOp() = verifyNoOp("today-order")
    @Test fun detailHostingKeepsEffectiveUndoAfterNoOp() = verifyNoOp("detail-hosting")
    @Test fun detailShortKeepsEffectiveUndoAfterNoOp() = verifyNoOp("detail-short")

    private fun verifyNoOp(entry: String) = runTest {
        val rules = GameRefreshRulesDomain(ZoneId.of("Asia/Shanghai"), LocalTime.of(4, 0),
            DayOfWeek.MONDAY, LocalTime.of(4, 0))
        val date = LocalDate.of(2026, 10, 17)
        val order = Order("order", "account-1", if (entry == "detail-short") OrderType.SHORT_TASK else OrderType.HOSTING, "test", null,
            PaymentMode.ONE_TIME, null, null, rules, fixedInstant, null)
        val period = HostingPeriod("period", "order", 0, date, date.plusDays(5), 100,
            rules, fixedInstant, null)
        val detail = OrderDetail(order, owner(), account(), emptyList(), if (order.isHosting) listOf(period) else emptyList(),
            emptyList(), emptyList(), date, fixedInstant)
        val fake = FakeOrderRepository()
        fake.details.value = mapOf("order" to detail)
        fake.hosting.value = listOf(TodayHostingOrder(order, "owner-1", "001", "test",
            "account-1", "test", "test", period, date,
            HostingStatus.ACTIVE, emptyList()))
        var calls = 0
        var undone: String? = null
        fun next(): BulkCompletionResult {
            calls += 1
            return BulkCompletionResult(if (calls == 1) "effective" else "empty", if (calls == 1) 1 else 0, 0, false)
        }
        val repository = object : OrderRepository by fake {
            override suspend fun completeHostingOrder(orderId: String, operationId: String,
                displayed: DisplayedHostingSnapshot) = next()
            override suspend fun completeShortOrder(orderId: String, operationId: String) = next()
            override suspend fun undoBatch(batchId: String): UndoResult {
                undone = batchId
                return UndoResult(1, emptyList(), false)
            }
        }
        val today = TodayViewModel(FixedTimeProvider(), repository)
        val attachments = object : AttachmentRepository {
            override fun observeForOrder(orderId: String) = flowOf(emptyList<Attachment>())
            override suspend fun addAttachment(taskDefinitionId: String?, occurrenceId: String?, sourceUri: Uri): Attachment = error("unused")
            override suspend fun replaceAttachment(attachmentId: String, sourceUri: Uri): Attachment = error("unused")
            override suspend fun deleteAttachment(attachmentId: String) = Unit
            override fun fileFor(attachment: Attachment): File = error("unused")
            override suspend fun cleanupOrphans() = 0
        }
        val vm = OrderDetailViewModel(SavedStateHandle(mapOf(Routes.ORDER_ID_ARG to "order")), repository, attachments)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { today.uiState.collect() }
        runCurrent()
        fun complete() = when (entry) {
            "today-order" -> today.completeHostingOrder("order")
            "detail-hosting" -> vm.completeHostingOrder()
            else -> vm.completeShortOrder()
        }
        complete()
        runCurrent()
        complete()
        runCurrent()
        assertEquals(2, calls)
        assertEquals("effective", if (entry == "today-order") today.uiState.value.lastBatchId else vm.uiState.value.lastBatchId)
        if (entry == "today-order") today.undoLastBatch() else vm.undoLastBatch()
        runCurrent()
        assertEquals("effective", undone)
    }
}
