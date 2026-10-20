package com.kuankuan.daigan.review

import androidx.lifecycle.SavedStateHandle
import com.kuankuan.daigan.data.model.ShortOrderDraft
import com.kuankuan.daigan.data.model.OrderType
import com.kuankuan.daigan.data.repository.OrderRepository
import com.kuankuan.daigan.feature.orders.NewOrderViewModel
import com.kuankuan.daigan.testing.FakeGameAccountRepository
import com.kuankuan.daigan.testing.FakeGameTemplateRepository
import com.kuankuan.daigan.testing.FakeOrderRepository
import com.kuankuan.daigan.testing.FakeOwnerRepository
import com.kuankuan.daigan.testing.FixedTimeProvider
import com.kuankuan.daigan.testing.MainDispatcherRule
import com.kuankuan.daigan.testing.account
import com.kuankuan.daigan.testing.owner
import com.kuankuan.daigan.ui.navigation.Routes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Submission identity must remain stable until successful navigation is acknowledged. */
@OptIn(ExperimentalCoroutinesApi::class)
class Phase3SubmissionReviewTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun repeatedTapBeforeLeavingSuccessfulFormMustNotCreateANewCommand() = runTest {
        val owners = FakeOwnerRepository().apply { ownersById["owner-1"] = owner() }
        val accounts = FakeGameAccountRepository().apply { accountsById["account-1"] = account() }
        val commands = mutableListOf<ShortOrderDraft>()
        val orders = object : OrderRepository by FakeOrderRepository() {
            override suspend fun createShortOrder(draft: ShortOrderDraft): String {
                commands += draft
                return "committed-order"
            }
        }
        val vm = NewOrderViewModel(
            SavedStateHandle(mapOf(Routes.OWNER_ID_ARG to "owner-1", Routes.ACCOUNT_ID_ARG to "account-1")),
            owners, accounts, orders, FixedTimeProvider(),
            FakeGameTemplateRepository(),
        )
        runCurrent()
        vm.selectType(OrderType.SHORT_TASK)
        vm.updatePrice("100.00")
        vm.updateTask(0) { it.copy(title = "推进主线") }
        vm.setInitialPaymentEnabled(true)
        vm.updateInitialAmount("100.00")

        vm.submit()
        runCurrent()
        assertEquals(1, commands.size)
        // Success has committed, but the form is still present and its navigation
        // event has not been consumed. A second queued tap is not a new order intent.
        vm.submit()
        runCurrent()
        assertEquals("The successful form must not allocate a second creation/payment identity", 1,
            commands.map { it.creationOperationId }.distinct().size)
    }
}
