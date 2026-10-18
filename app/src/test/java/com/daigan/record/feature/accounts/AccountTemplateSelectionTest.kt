package com.daigan.record.feature.accounts

import androidx.lifecycle.SavedStateHandle
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.testing.FakeGameAccountRepository
import com.daigan.record.testing.FakeGameTemplateRepository
import com.daigan.record.testing.FakeOwnerRepository
import com.daigan.record.testing.MainDispatcherRule
import com.daigan.record.testing.TemplateFixtures
import com.daigan.record.testing.account
import com.daigan.record.testing.owner
import com.daigan.record.ui.navigation.Routes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Template selection on the account form is an optional creation default only. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountTemplateSelectionTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val templates = FakeGameTemplateRepository().apply {
        put(TemplateFixtures.template(id = "tpl-1", gameName = "重返未来：1999"))
    }
    private val owners = FakeOwnerRepository().apply { ownersById["owner-1"] = owner() }
    private val accounts = FakeGameAccountRepository()

    private fun TestScope.viewModel(accountId: String? = null): AccountFormViewModel {
        val viewModel = AccountFormViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(Routes.OWNER_ID_ARG to "owner-1", Routes.ACCOUNT_ID_ARG to accountId),
            ),
            ownerRepository = owners,
            accountRepository = accounts,
            templateRepository = templates,
        )
        runCurrent()
        return viewModel
    }

    @Test
    fun `selecting a template fills an empty game name and becomes the default`() = runTest {
        val viewModel = viewModel()
        assertEquals(1, viewModel.uiState.value.templates.size)

        viewModel.selectTemplate("tpl-1")
        assertEquals("tpl-1", viewModel.uiState.value.draft.defaultTemplateId)
        assertEquals("重返未来：1999", viewModel.uiState.value.draft.gameName)

        viewModel.onDraftChange {
            it.copy(server = "官服", uid = "1001", loginAccount = "demo")
        }
        viewModel.save()
        runCurrent()

        val saved = accounts.createdDrafts.single()
        assertEquals("tpl-1", saved.defaultTemplateId)
        assertEquals("重返未来：1999", saved.gameName)
        assertEquals(LoginMethod.VERIFICATION_CODE, saved.loginMethod)
    }

    @Test
    fun `a typed game name is never overwritten by template selection`() = runTest {
        val viewModel = viewModel()
        viewModel.onDraftChange { it.copy(gameName = "鸣潮") }

        viewModel.selectTemplate("tpl-1")

        assertEquals("鸣潮", viewModel.uiState.value.draft.gameName)
        assertEquals("tpl-1", viewModel.uiState.value.draft.defaultTemplateId)
    }

    @Test
    fun `clearing the template keeps the account usable`() = runTest {
        val viewModel = viewModel()
        viewModel.selectTemplate("tpl-1")
        viewModel.selectTemplate(null)
        viewModel.onDraftChange {
            it.copy(server = "官服", uid = "1001", loginAccount = "demo")
        }
        viewModel.save()
        runCurrent()

        assertNull(viewModel.uiState.value.draft.defaultTemplateId)
        assertNull(accounts.createdDrafts.single().defaultTemplateId)
    }

    @Test
    fun `a deleted default template is ignored when editing an account`() = runTest {
        accounts.accountsById["account-1"] = account(defaultTemplateId = "deleted-template")
        val viewModel = viewModel("account-1")

        assertNull(viewModel.uiState.value.draft.defaultTemplateId)
        assertNull(viewModel.uiState.value.templateName)
        assertEquals("account-1", accounts.accountsById["account-1"]?.id)
    }
}
