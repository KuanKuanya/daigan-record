package com.kuankuan.daigan.feature.orders

import androidx.lifecycle.SavedStateHandle
import com.kuankuan.daigan.data.model.HostingOrderDraft
import com.kuankuan.daigan.data.model.OrderCommandFingerprint
import com.kuankuan.daigan.data.model.OrderType
import com.kuankuan.daigan.data.model.ShortOrderDraft
import com.kuankuan.daigan.data.repository.OrderRepository
import com.kuankuan.daigan.testing.FakeGameAccountRepository
import com.kuankuan.daigan.testing.FakeGameTemplateRepository
import com.kuankuan.daigan.testing.FakeOrderRepository
import com.kuankuan.daigan.testing.FakeOwnerRepository
import com.kuankuan.daigan.testing.FixedTimeProvider
import com.kuankuan.daigan.testing.MainDispatcherRule
import com.kuankuan.daigan.testing.account
import com.kuankuan.daigan.testing.owner
import com.kuankuan.daigan.ui.navigation.Routes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Submission identity lifecycle for one visible new-order form.
 *
 * A committed creation is terminal for that form instance: the compiled-in behaviour
 * under test is that only navigation acknowledgement or an explicit "new creation"
 * action may leave the terminal state, and that no repeated tap on the successful form
 * ever reaches the repository with fresh ids.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewOrderSubmissionLifecycleTest {

    @get:Rule
    val main = MainDispatcherRule()

    /** Records every create command and can block or fail the first attempt. */
    private class RecordingOrderRepository : OrderRepository by FakeOrderRepository() {
        val shortDrafts = mutableListOf<ShortOrderDraft>()
        val hostingDrafts = mutableListOf<HostingOrderDraft>()
        var gate: CompletableDeferred<Unit>? = null
        var failuresRemaining: Int = 0

        override suspend fun createShortOrder(draft: ShortOrderDraft): String {
            shortDrafts += draft
            gate?.await()
            if (failuresRemaining > 0) {
                failuresRemaining -= 1
                error("仓储暂时失败")
            }
            return "order-${shortDrafts.size}"
        }

        override suspend fun createHostingOrder(draft: HostingOrderDraft): String {
            hostingDrafts += draft
            gate?.await()
            if (failuresRemaining > 0) {
                failuresRemaining -= 1
                error("仓储暂时失败")
            }
            return "hosting-${hostingDrafts.size}"
        }
    }

    private fun savedState(): SavedStateHandle = SavedStateHandle(
        mapOf(Routes.OWNER_ID_ARG to "owner-1", Routes.ACCOUNT_ID_ARG to "account-1"),
    )

    private fun TestScope.newOrderViewModel(
        repository: OrderRepository,
        savedStateHandle: SavedStateHandle = savedState(),
    ): NewOrderViewModel {
        val owners = FakeOwnerRepository().apply { ownersById["owner-1"] = owner() }
        val accounts = FakeGameAccountRepository().apply { accountsById["account-1"] = account() }
        val viewModel = NewOrderViewModel(
            savedStateHandle,
            owners,
            accounts,
            repository,
            FixedTimeProvider(),
            FakeGameTemplateRepository(),
        )
        runCurrent()
        viewModel.selectType(OrderType.SHORT_TASK)
        viewModel.updatePrice("100.00")
        viewModel.updateTask(0) { it.copy(title = "推进主线") }
        viewModel.setInitialPaymentEnabled(true)
        viewModel.updateInitialAmount("100.00")
        return viewModel
    }

    @Test
    fun repeatedTapWhileSubmissionIsInFlightStartsOneCommand() = runTest {
        val repository = RecordingOrderRepository().apply { gate = CompletableDeferred() }
        val viewModel = newOrderViewModel(repository)

        viewModel.submit()
        runCurrent()
        assertTrue("the in-flight gate must be held synchronously", viewModel.uiState.value.saving)
        assertEquals(1, repository.shortDrafts.size)

        viewModel.submit()
        viewModel.submit()
        runCurrent()
        assertEquals("queued taps must not start another command", 1, repository.shortDrafts.size)

        repository.gate?.complete(Unit)
        repository.gate = null
        runCurrent()
        assertEquals(1, repository.shortDrafts.size)
        assertEquals("order-1", viewModel.uiState.value.createdOrderId)
        assertFalse(viewModel.uiState.value.saving)
    }

    @Test
    fun successBeforeAnyCollectorSubscribesIsStillObservable() = runTest {
        val repository = RecordingOrderRepository()
        val viewModel = newOrderViewModel(repository)

        // No collector exists while the command commits.
        viewModel.submit()
        runCurrent()
        assertEquals(1, repository.shortDrafts.size)

        // A collector that attaches afterwards still sees the committed destination.
        val observed = viewModel.uiState.first()
        assertEquals("order-1", observed.createdOrderId)
        assertTrue(observed.createdNavigationPending)
        assertTrue(observed.isCreated)
        assertEquals(1, repository.shortDrafts.size)
    }

    @Test
    fun collectorReattachmentKeepsTheCommittedIdentityUntilAcknowledged() = runTest {
        val repository = RecordingOrderRepository()
        val viewModel = newOrderViewModel(repository)
        viewModel.submit()
        runCurrent()

        val firstCollector = mutableListOf<NewOrderUiState>()
        val firstJob = launch { viewModel.uiState.collect { firstCollector += it } }
        runCurrent()
        assertEquals("order-1", firstCollector.last().createdOrderId)
        firstJob.cancel()

        // Reattachment (for example after the screen is recreated) sees the same result.
        val secondCollector = mutableListOf<NewOrderUiState>()
        val secondJob = launch { viewModel.uiState.collect { secondCollector += it } }
        runCurrent()
        assertEquals("order-1", secondCollector.last().createdOrderId)
        assertTrue(secondCollector.last().createdNavigationPending)

        // Acknowledging stops the navigation signal but never drops the committed form.
        viewModel.acknowledgeCreatedNavigation("another-order")
        assertTrue(viewModel.uiState.value.createdNavigationPending)
        viewModel.acknowledgeCreatedNavigation("order-1")
        assertFalse(viewModel.uiState.value.createdNavigationPending)
        assertEquals("order-1", viewModel.uiState.value.createdOrderId)

        // A repeated tap after the acknowledge is still the same successful form.
        viewModel.submit()
        runCurrent()
        assertEquals(1, repository.shortDrafts.size)
        assertEquals("order-1", viewModel.uiState.value.createdOrderId)
        secondJob.cancel()
    }

    @Test
    fun restoredSuccessfulFormRecoversTheDestinationWithoutSubmittingAgain() = runTest {
        val repository = RecordingOrderRepository()
        val handle = savedState()
        val viewModel = newOrderViewModel(repository, handle)
        viewModel.submit()
        runCurrent()
        assertEquals(1, repository.shortDrafts.size)

        // Restoration is a second ViewModel instance over the same saved state, which is
        // what process death plus navigation-state restoration produces.
        val restoredRepository = RecordingOrderRepository()
        val restored = newOrderViewModel(restoredRepository, handle)
        assertEquals("order-1", restored.uiState.value.createdOrderId)
        assertTrue(restored.uiState.value.createdNavigationPending)

        restored.submit()
        runCurrent()
        assertEquals("a restored successful form must not submit again", 0, restoredRepository.shortDrafts.size)

        // Even after the destination was consumed once, the form stays terminal.
        restored.acknowledgeCreatedNavigation("order-1")
        restored.submit()
        runCurrent()
        assertEquals(0, restoredRepository.shortDrafts.size)
        assertEquals("order-1", restored.uiState.value.createdOrderId)
    }

    @Test
    fun failedSubmissionRetriesWithIdenticalIdentitiesAndReceiptTime() = runTest {
        val repository = RecordingOrderRepository().apply { failuresRemaining = 1 }
        val viewModel = newOrderViewModel(repository)

        viewModel.submit()
        runCurrent()
        assertEquals(1, repository.shortDrafts.size)
        assertNotNull(viewModel.uiState.value.saveError)
        assertNull(viewModel.uiState.value.createdOrderId)
        assertFalse(viewModel.uiState.value.saving)

        viewModel.submit()
        runCurrent()
        assertEquals(2, repository.shortDrafts.size)

        val first = repository.shortDrafts[0]
        val retry = repository.shortDrafts[1]
        assertEquals(first.creationOperationId, retry.creationOperationId)
        assertEquals(first.initialPayment?.operationId, retry.initialPayment?.operationId)
        assertEquals(first.initialPayment?.receivedAt, retry.initialPayment?.receivedAt)
        assertNull(viewModel.uiState.value.saveError)
        assertEquals("order-2", viewModel.uiState.value.createdOrderId)
    }

    @Test
    fun explicitNewCreationFlowAllocatesFreshIdentities() = runTest {
        val repository = RecordingOrderRepository()
        val viewModel = newOrderViewModel(repository)
        viewModel.submit()
        runCurrent()
        assertEquals(1, repository.shortDrafts.size)

        // Repeated taps on the successful form do nothing.
        viewModel.submit()
        runCurrent()
        assertEquals(1, repository.shortDrafts.size)

        // An explicit user action is the only way to start another order.
        viewModel.startNewCreation()
        assertNull(viewModel.uiState.value.createdOrderId)
        assertFalse(viewModel.uiState.value.createdNavigationPending)

        viewModel.submit()
        runCurrent()
        assertEquals(2, repository.shortDrafts.size)
        val first = repository.shortDrafts[0]
        val second = repository.shortDrafts[1]
        assertNotEquals(first.creationOperationId, second.creationOperationId)
        assertNotEquals(first.initialPayment?.operationId, second.initialPayment?.operationId)
        // Otherwise identical: the user did not have to retype the order.
        assertEquals(first.title, second.title)
        assertEquals(first.priceYuan, second.priceYuan)
        assertEquals("order-2", viewModel.uiState.value.createdOrderId)
    }

    @Test
    fun editingThePayloadAfterFailureStillStartsANewCommand() = runTest {
        val repository = RecordingOrderRepository().apply { failuresRemaining = 1 }
        val viewModel = newOrderViewModel(repository)

        viewModel.submit()
        runCurrent()
        assertEquals(1, repository.shortDrafts.size)

        viewModel.updatePrice("120.00")
        viewModel.updateInitialAmount("120.00")
        viewModel.submit()
        runCurrent()
        assertEquals(2, repository.shortDrafts.size)
        assertNotEquals(
            repository.shortDrafts[0].creationOperationId,
            repository.shortDrafts[1].creationOperationId,
        )
    }
    @Test fun legacyPendingShortCommandRetainsIdentityAndLegacyCalendarOnRetry() = runTest {
        val handle = savedState()
        val originalRepository = RecordingOrderRepository().apply { failuresRemaining = 1 }
        val original = newOrderViewModel(originalRepository, handle)
        original.submit()
        runCurrent()
        val legacyDraft = originalRepository.shortDrafts.single().copy(refresh = null)
        // Simulate a saved Phase 3/4 command: no provenance or calendar-mode additions.
        handle["new-order.pending-command"] = handle.get<String>("new-order.pending-command")!!
            .lineSequence().filterNot {
                it.startsWith("sourceTemplate") || it.startsWith("explicitShortCalendar=")
            }.joinToString("\n")
        handle["new-order.pending-fingerprint"] = OrderCommandFingerprint.short(legacyDraft)
        val repository = RecordingOrderRepository()
        val restored = newOrderViewModel(repository, handle)
        restored.submit()
        runCurrent()
        assertEquals(legacyDraft, repository.shortDrafts.single())
        assertEquals("order-1", restored.uiState.value.createdOrderId)
    }

}
