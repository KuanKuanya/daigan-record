package com.daigan.record.feature.accounts

import androidx.lifecycle.SavedStateHandle
import com.daigan.record.data.model.AccountField
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.data.repository.ARCHIVED_OWNER_ACCOUNT_MESSAGE
import com.daigan.record.testing.FakeGameAccountRepository
import com.daigan.record.testing.FakeGameTemplateRepository
import com.daigan.record.testing.FakeOwnerRepository
import com.daigan.record.testing.MainDispatcherRule
import com.daigan.record.testing.account
import com.daigan.record.testing.owner
import com.daigan.record.ui.navigation.Routes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AccountFormViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val ownerRepository = FakeOwnerRepository()
    private val accountRepository = FakeGameAccountRepository()

    private fun viewModel(accountId: String? = null): AccountFormViewModel {
        ownerRepository.ownersById["owner-1"] = owner(number = 1, nickname = "小满")
        return AccountFormViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(
                    Routes.OWNER_ID_ARG to "owner-1",
                    Routes.ACCOUNT_ID_ARG to accountId,
                ),
            ),
            ownerRepository = ownerRepository,
            accountRepository = accountRepository,
            templateRepository = FakeGameTemplateRepository(),
        )
    }

    @Test
    fun `new account form loads the owner nickname`() = runTest {
        val viewModel = viewModel()

        assertEquals("小满", viewModel.uiState.value.ownerNickname)
        assertEquals(LoginMethod.VERIFICATION_CODE, viewModel.uiState.value.draft.loginMethod)
    }

    @Test
    fun `editing loads the stored account`() = runTest {
        accountRepository.accountsById["account-1"] = account()

        val viewModel = viewModel(accountId = "account-1")

        assertTrue(viewModel.uiState.value.isEdit)
        assertEquals("重返未来：1999", viewModel.uiState.value.draft.gameName)
        assertEquals(LoginMethod.PASSWORD, viewModel.uiState.value.draft.loginMethod)
    }

    @Test
    fun `switching to verification code login clears the entered password`() = runTest {
        accountRepository.accountsById["account-1"] = account()
        val viewModel = viewModel(accountId = "account-1")
        assertEquals("demo1999", viewModel.uiState.value.draft.password)

        viewModel.selectLoginMethod(LoginMethod.VERIFICATION_CODE)

        assertEquals(LoginMethod.VERIFICATION_CODE, viewModel.uiState.value.draft.loginMethod)
        assertEquals("", viewModel.uiState.value.draft.password)
    }

    @Test
    fun `save refuses blank required fields`() = runTest {
        val viewModel = viewModel()

        viewModel.save()

        val errors = viewModel.uiState.value.errors
        assertTrue(errors.containsKey(AccountField.GAME_NAME))
        assertTrue(errors.containsKey(AccountField.UID))
        assertTrue(accountRepository.createdDrafts.isEmpty())
        assertFalse(viewModel.uiState.value.completed)
    }

    @Test
    fun `save submits a verification code account without a password`() = runTest {
        val viewModel = viewModel()
        viewModel.onDraftChange {
            it.copy(
                gameName = "鸣潮",
                server = "国服",
                uid = "10002136",
                loginAccount = "demo-acheng",
            )
        }

        viewModel.save()

        assertEquals(1, accountRepository.createdDrafts.size)
        val submitted = accountRepository.createdDrafts.single()
        assertEquals(LoginMethod.VERIFICATION_CODE, submitted.loginMethod)
        assertEquals("", submitted.password)
        assertTrue(viewModel.uiState.value.completed)
        assertNull(viewModel.uiState.value.saveError)
    }

    @Test
    fun `save submits a password account with the password`() = runTest {
        val viewModel = viewModel()
        viewModel.onDraftChange {
            it.copy(
                gameName = "鸣潮",
                server = "国服",
                uid = "10002136",
                loginAccount = "demo-acheng",
                password = "secret",
            )
        }
        viewModel.selectLoginMethod(LoginMethod.PASSWORD)

        viewModel.save()

        assertEquals("secret", accountRepository.createdDrafts.single().password)
        assertTrue(viewModel.uiState.value.completed)
    }

    @Test
    fun `editing an account submits an update instead of a create`() = runTest {
        accountRepository.accountsById["account-1"] = account()
        val viewModel = viewModel(accountId = "account-1")
        viewModel.onDraftChange { it.copy(server = "渠道服") }

        viewModel.save()

        assertTrue(accountRepository.createdDrafts.isEmpty())
        assertEquals("渠道服", accountRepository.updatedDrafts.single().server)
    }

    /**
     * A form that was opened before the owner was archived must not submit anything:
     * the stale form is refused before the repository is even called.
     */
    @Test
    fun `an archived owner blocks a stale account form`() = runTest {
        ownerRepository.ownersById["owner-1"] = owner(number = 1, nickname = "小满", archived = true)
        val viewModel = AccountFormViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(
                    Routes.OWNER_ID_ARG to "owner-1",
                    Routes.ACCOUNT_ID_ARG to null,
                ),
            ),
            ownerRepository = ownerRepository,
            accountRepository = accountRepository,
            templateRepository = FakeGameTemplateRepository(),
        )
        viewModel.onDraftChange {
            it.copy(gameName = "鸣潮", server = "国服", uid = "10002136", loginAccount = "demo")
        }

        assertTrue(viewModel.uiState.value.ownerArchived)
        viewModel.save()

        assertTrue(accountRepository.createdDrafts.isEmpty())
        assertFalse(viewModel.uiState.value.completed)
        assertEquals(ARCHIVED_OWNER_ACCOUNT_MESSAGE, viewModel.uiState.value.saveError)
    }
}
