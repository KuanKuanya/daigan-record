package com.daigan.record.review

import androidx.lifecycle.SavedStateHandle
import com.daigan.record.data.model.ShortOrderDraft
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.repository.OrderRepository
import com.daigan.record.feature.orders.NewOrderViewModel
import com.daigan.record.testing.FakeGameAccountRepository
import com.daigan.record.testing.FakeOrderRepository
import com.daigan.record.testing.FakeOwnerRepository
import com.daigan.record.testing.FixedTimeProvider
import com.daigan.record.testing.MainDispatcherRule
import com.daigan.record.testing.account
import com.daigan.record.testing.owner
import com.daigan.record.ui.navigation.Routes
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
