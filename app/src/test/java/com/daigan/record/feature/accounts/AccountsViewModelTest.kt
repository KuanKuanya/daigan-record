package com.daigan.record.feature.accounts

import com.daigan.record.data.model.OwnerSummary
import com.daigan.record.testing.FakeOwnerRepository
import com.daigan.record.testing.MainDispatcherRule
import com.daigan.record.testing.account
import com.daigan.record.testing.owner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
        val viewModel = AccountsViewModel(repository)

        val state = viewModel.uiState.first { it is OwnersUiState.Success } as OwnersUiState.Success

        assertEquals(1, state.owners.size)
        assertEquals("小满", state.owners.single().owner.nickname)
        assertEquals(1, state.owners.single().activeAccountCount)
        assertEquals(1, state.archivedCount)
    }

    @Test
    fun `empty database produces an empty success state`() = runTest {
        val viewModel = AccountsViewModel(FakeOwnerRepository())

        val state = viewModel.uiState.first { it is OwnersUiState.Success } as OwnersUiState.Success

        assertTrue(state.owners.isEmpty())
        assertEquals(0, state.archivedCount)
    }
}
