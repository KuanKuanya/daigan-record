package com.kuankuan.daigan.feature.accounts

import com.kuankuan.daigan.data.model.OwnerSummary
import com.kuankuan.daigan.testing.FakeOwnerRepository
import com.kuankuan.daigan.testing.MainDispatcherRule
import com.kuankuan.daigan.testing.account
import com.kuankuan.daigan.testing.owner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.flow.collect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AccountsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `lists active owners with accounts and counts archived owners`() = runTest {
        val repository = FakeOwnerRepository()
        repository.activeOwners.value = listOf(
            OwnerSummary(owner(id = "o1", number = 1, nickname = "小满"), listOf(account())),
        )
        repository.archivedOwners.value = listOf(
            OwnerSummary(owner(id = "o2", number = 2, nickname = "阿橙", archived = true), emptyList()),
        )
        val viewModel = AccountsViewModel(repository, com.kuankuan.daigan.testing.FakeOrderRepository())

        val state = viewModel.uiState.first { it is OwnersUiState.Success } as OwnersUiState.Success

        assertEquals(1, state.owners.size)
        assertEquals("小满", state.owners.single().owner.nickname)
        assertEquals(1, state.owners.single().activeAccountCount)
        assertEquals(1, state.archivedCount)
    }

    @Test
    fun `empty database produces an empty success state`() = runTest {
        val viewModel = AccountsViewModel(FakeOwnerRepository(), com.kuankuan.daigan.testing.FakeOrderRepository())

        val state = viewModel.uiState.first { it is OwnersUiState.Success } as OwnersUiState.Success

        assertTrue(state.owners.isEmpty())
        assertEquals(0, state.archivedCount)
    }
    private fun shortOrder(ownerId: String, id: String = "short-$ownerId") =
        com.kuankuan.daigan.data.model.ShortWorkOrder(
            order = com.kuankuan.daigan.data.model.Order(
                id = id, accountId = "a-$ownerId",
                type = com.kuankuan.daigan.data.model.OrderType.SHORT_TASK,
                title = "短期单", priceCents = 100, paymentMode = com.kuankuan.daigan.data.model.PaymentMode.ONE_TIME,
                deadlineDate = null, notes = null, refresh = refresh(),
                createdAt = java.time.Instant.EPOCH, completedAt = null,
            ),
            ownerId = ownerId, ownerNumber = "B001", ownerNickname = "老板", accountId = "a-$ownerId",
            gameName = "通用", server = "国服", tasks = emptyList(), today = java.time.LocalDate.of(2026, 10, 19),
        )

    private fun refresh() = com.kuankuan.daigan.data.model.GameRefreshRulesDomain(
        java.time.ZoneId.of("Asia/Shanghai"), java.time.LocalTime.of(5, 0),
        java.time.DayOfWeek.MONDAY, java.time.LocalTime.of(5, 0),
    )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `unfinished orders pin owners and final settlement restores original order reactively`() = runTest {
        val owners = FakeOwnerRepository()
        owners.activeOwners.value = listOf(
            OwnerSummary(owner(id = "o1", number = 1), emptyList()),
            OwnerSummary(owner(id = "o2", number = 2), emptyList()),
            OwnerSummary(owner(id = "o3", number = 3), emptyList()),
        )
        val orders = com.kuankuan.daigan.testing.FakeOrderRepository()
        val first = shortOrder("o3", "first")
        val second = shortOrder("o3", "second")
        orders.shortOrders.value = listOf(first, second)
        val viewModel = AccountsViewModel(owners, orders)
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
        advanceUntilIdle()
        fun state() = viewModel.uiState.value as OwnersUiState.Success
        assertEquals(listOf("o3", "o1", "o2"), state().owners.map { it.owner.id })
        assertEquals(2, state().owners.first().unfinishedOrderCount)
        orders.shortOrders.value = listOf(second)
        advanceUntilIdle()
        assertEquals("o3", state().owners.first().owner.id)
        assertEquals(1, state().owners.first().unfinishedOrderCount)
        orders.shortOrders.value = emptyList()
        advanceUntilIdle()
        assertEquals(listOf("o1", "o2", "o3"), state().owners.map { it.owner.id })
        assertTrue(state().owners.all { it.unfinishedOrderCount == 0 })
    }

    @Test
    fun `hosting service is unfinished until expiry even with no tasks today`() = runTest {
        val owners = FakeOwnerRepository()
        owners.activeOwners.value = listOf(
            OwnerSummary(owner(id = "o1", number = 1), emptyList()),
            OwnerSummary(owner(id = "o2", number = 2), emptyList()),
        )
        val orders = com.kuankuan.daigan.testing.FakeOrderRepository()
        val base = shortOrder("o2").order.copy(type = com.kuankuan.daigan.data.model.OrderType.HOSTING)
        val date = java.time.LocalDate.of(2026, 10, 19)
        fun hosting(status: com.kuankuan.daigan.core.time.HostingStatus) = com.kuankuan.daigan.data.model.TodayHostingOrder(
            order = base, ownerId = "o2", ownerNumber = "B002", ownerNickname = "老板", accountId = "a-o2",
            gameName = "通用", server = "国服",
            period = com.kuankuan.daigan.data.model.HostingPeriod("period", base.id, 0, date, date.plusDays(3), 100,
                refresh(), java.time.Instant.EPOCH, null),
            today = date, status = status, tasks = emptyList(),
        )
        orders.hosting.value = listOf(hosting(com.kuankuan.daigan.core.time.HostingStatus.ACTIVE))
        var state = AccountsViewModel(owners, orders).uiState.first { it is OwnersUiState.Success } as OwnersUiState.Success
        assertEquals("o2", state.owners.first().owner.id)
        orders.hosting.value = listOf(hosting(com.kuankuan.daigan.core.time.HostingStatus.NOT_STARTED))
        state = AccountsViewModel(owners, orders).uiState.first { it is OwnersUiState.Success } as OwnersUiState.Success
        assertEquals(1, state.owners.first().unfinishedOrderCount)
        orders.hosting.value = listOf(hosting(com.kuankuan.daigan.core.time.HostingStatus.EXPIRED))
        state = AccountsViewModel(owners, orders).uiState.first { it is OwnersUiState.Success } as OwnersUiState.Success
        assertEquals(listOf("o1", "o2"), state.owners.map { it.owner.id })
        assertTrue(state.owners.all { it.unfinishedOrderCount == 0 })
    }

}
