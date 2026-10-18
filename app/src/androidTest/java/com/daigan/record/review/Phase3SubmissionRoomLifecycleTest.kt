package com.daigan.record.review

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.ShortOrderDraft
import com.daigan.record.data.repository.OrderRepository
import com.daigan.record.feature.orders.NewOrderViewModel
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-Room proof of the successful-form contract: a repeated tap on a committed form
 * must leave exactly one order, one creation-operation row and one initial receipt.
 * A genuinely new creation flow may create a second, otherwise identical order.
 *
 * The ViewModel runs on the real Android main dispatcher here, exactly like the app;
 * the test thread only dispatches UI actions and waits for observable conditions.
 * Process death is not simulated: the restored instance reuses one [SavedStateHandle],
 * which is what restoration supplies.
 */
@RunWith(AndroidJUnit4::class)
class Phase3SubmissionRoomLifecycleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var env: OrderTestEnvironment

    private class RecordingOrderRepository(
        private val delegate: OrderRepository,
    ) : OrderRepository by delegate {
        val shortDrafts = mutableListOf<ShortOrderDraft>()

        override suspend fun createShortOrder(draft: ShortOrderDraft): String {
            shortDrafts += draft
            return delegate.createShortOrder(draft)
        }
    }

    @Before
    fun setUp() {
        env = OrderTestEnvironment(context)
    }

    @After
    fun tearDown() {
        env.close()
    }

    private suspend fun newViewModel(
        repository: OrderRepository,
        ownerId: String,
        accountId: String,
    ): NewOrderViewModel {
        val viewModel = NewOrderViewModel(
            SavedStateHandle(
                mapOf(Routes.OWNER_ID_ARG to ownerId, Routes.ACCOUNT_ID_ARG to accountId),
            ),
            env.ownerRepository,
            env.accountRepository,
            repository,
            env.clock,
            env.templateRepository,
        )
        await("owner and account loaded") { !viewModel.uiState.value.loading }
        main {
            viewModel.selectType(OrderType.SHORT_TASK)
            viewModel.updatePrice("100.00")
            viewModel.updateTask(0) { it.copy(title = "推进主线") }
            viewModel.setInitialPaymentEnabled(true)
            viewModel.updateInitialAmount("100.00")
        }
        return viewModel
    }

    @Test
    fun repeatedTapOnSuccessfulFormLeavesOneOrderOneOperationAndOneReceipt() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val repository = RecordingOrderRepository(env.orderRepository)
        val viewModel = newViewModel(repository, owner.id, account.id)

        main { viewModel.submit() }
        await("first creation committed") { viewModel.uiState.value.createdOrderId != null }
        val committedOrderId = checkNotNull(viewModel.uiState.value.createdOrderId)
        assertEquals(1, repository.shortDrafts.size)

        // The form is still visible and its navigation has not been acknowledged.
        main { viewModel.submit() }
        // ... and even after the destination was consumed once, before disposal.
        main { viewModel.acknowledgeCreatedNavigation(committedOrderId) }
        main { viewModel.submit() }
        assertEquals("the committed form must not submit again", 1, repository.shortDrafts.size)
        assertEquals(committedOrderId, viewModel.uiState.value.createdOrderId)

        val orders = env.database.orderDao().findForAccount(account.id)
        assertEquals("exactly one order", 1, orders.size)
        assertEquals(committedOrderId, orders.single().id)
        assertEquals("exactly one creation operation", 1, env.database.creationOperationDao().count())
        val operation = env.database.creationOperationDao().findAll().single()
        assertEquals(committedOrderId, operation.orderId)
        val receipts = env.database.receiptDao().findForOrder(committedOrderId)
        assertEquals("exactly one initial receipt", 1, receipts.size)
        assertNotNull(operation.receiptId)
        assertEquals(operation.receiptId, receipts.single().id)
    }

    @Test
    fun explicitNewCreationCreatesASecondIdenticalOrderWithFreshIdentities() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val repository = RecordingOrderRepository(env.orderRepository)
        val viewModel = newViewModel(repository, owner.id, account.id)

        main { viewModel.submit() }
        await("first creation committed") { viewModel.uiState.value.createdOrderId != null }
        val firstOrderId = checkNotNull(viewModel.uiState.value.createdOrderId)

        main { viewModel.startNewCreation() }
        main { viewModel.submit() }
        await("second creation committed") {
            viewModel.uiState.value.createdOrderId?.let { it != firstOrderId } == true
        }
        val secondOrderId = checkNotNull(viewModel.uiState.value.createdOrderId)

        assertEquals(2, repository.shortDrafts.size)
        assertNotEquals(
            repository.shortDrafts[0].creationOperationId,
            repository.shortDrafts[1].creationOperationId,
        )
        assertEquals(2, env.database.orderDao().findForAccount(account.id).size)
        assertEquals(2, env.database.creationOperationDao().count())
        assertNotEquals(firstOrderId, secondOrderId)
        assertEquals(1, env.database.receiptDao().findForOrder(firstOrderId).size)
        assertEquals(1, env.database.receiptDao().findForOrder(secondOrderId).size)
    }

    /** ViewModel actions run on the real main dispatcher, like every production tap. */
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    private suspend fun await(description: String, condition: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!condition()) delay(10) }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            assertTrue("timed out waiting for $description", false)
        }
    }
}
