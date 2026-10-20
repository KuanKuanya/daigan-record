package com.kuankuan.daigan.feature.accounts

import androidx.lifecycle.SavedStateHandle
import com.kuankuan.daigan.testing.FakeGameAccountRepository
import com.kuankuan.daigan.testing.FakeOwnerRepository
import com.kuankuan.daigan.testing.MainDispatcherRule
import com.kuankuan.daigan.testing.owner
import com.kuankuan.daigan.ui.navigation.Routes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OwnerFormViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val ownerRepository = FakeOwnerRepository()

    private fun viewModel(ownerId: String? = null): OwnerFormViewModel = OwnerFormViewModel(
        savedStateHandle = SavedStateHandle(mapOf(Routes.OWNER_ID_ARG to ownerId)),
        ownerRepository = ownerRepository,
    )

    @Test
    fun `new owner form starts empty with an automatic number`() = runTest {
        val viewModel = viewModel()

        assertFalse(viewModel.uiState.value.isEdit)
        assertEquals("自动生成", viewModel.uiState.value.numberLabel)
    }

    @Test
    fun `blank nickname is rejected before hitting the repository`() = runTest {
        val viewModel = viewModel()

        viewModel.save()

        assertEquals("请填写老板昵称", viewModel.uiState.value.nicknameError)
        assertTrue(ownerRepository.createdNicknames.isEmpty())
        assertFalse(viewModel.uiState.value.completed)
    }

    @Test
    fun `creating an owner trims the nickname and completes`() = runTest {
        val viewModel = viewModel()
        viewModel.onNicknameChange("  小满  ")

        viewModel.save()

        assertEquals(listOf("小满"), ownerRepository.createdNicknames)
        assertTrue(viewModel.uiState.value.completed)
    }

    @Test
    fun `editing shows the immutable number and renames the same owner`() = runTest {
        ownerRepository.ownersById["owner-3"] = owner(id = "owner-3", number = 3, nickname = "北辰")
        val viewModel = viewModel(ownerId = "owner-3")

        assertEquals("B003", viewModel.uiState.value.numberLabel)
        assertEquals("北辰", viewModel.uiState.value.nickname)

        viewModel.onNicknameChange("北辰（老客）")
        viewModel.save()

        assertEquals(listOf("owner-3" to "北辰（老客）"), ownerRepository.renamed)
        assertEquals("B003", viewModel.uiState.value.numberLabel)
    }
}
