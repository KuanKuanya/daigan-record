package com.kuankuan.daigan.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kuankuan.daigan.data.model.ArchivedAccount
import com.kuankuan.daigan.data.model.OwnerSummary
import com.kuankuan.daigan.feature.accounts.ArchivedRecordsScreen
import com.kuankuan.daigan.feature.accounts.ArchivedRecordsUiState
import com.kuankuan.daigan.testing.account
import com.kuankuan.daigan.testing.owner
import com.kuankuan.daigan.ui.theme.DaiganHandbookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The archived-record summary must only claim what is actually known: accounts archived
 * with the owner are counted, accounts archived separately are not presented as part of
 * the owner archive.
 */
@RunWith(AndroidJUnit4::class)
class ArchivedRecordsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(uiState: ArchivedRecordsUiState) {
        composeTestRule.setContent {
            DaiganHandbookTheme {
                ArchivedRecordsScreen(
                    uiState = uiState,
                    onBack = {},
                    onRestoreOwner = {},
                    onRestoreAccount = {},
                )
            }
        }
    }

    @Test
    fun archivedOwnerCountsOnlyAccountsArchivedTogetherWithIt() {
        val summary = OwnerSummary(
            owner = owner(archived = true),
            accounts = listOf(
                account(id = "a1", archived = true, archivedWithOwner = true),
                account(id = "a2", archived = true, archivedWithOwner = true),
                account(id = "a3", archived = true, archivedWithOwner = false),
            ),
        )

        setContent(ArchivedRecordsUiState(isLoading = false, archivedOwners = listOf(summary)))

        composeTestRule.onNodeWithText("共 3 个游戏账号，其中 2 个随老板归档").assertExists()
        composeTestRule.onNodeWithText("3 个随老板归档的游戏账号").assertDoesNotExist()
        composeTestRule.onNodeWithText("恢复老板").assertExists()
    }

    @Test
    fun ownerWithoutIndependentArchivesStatesTheExactCount() {
        val summary = OwnerSummary(
            owner = owner(archived = true),
            accounts = listOf(
                account(id = "a1", archived = true, archivedWithOwner = true),
                account(id = "a2", archived = true, archivedWithOwner = true),
            ),
        )

        setContent(ArchivedRecordsUiState(isLoading = false, archivedOwners = listOf(summary)))

        composeTestRule.onNodeWithText("2 个随老板归档的游戏账号").assertExists()
    }

    @Test
    fun archivedOwnerWithoutAccountsSaysSo() {
        setContent(
            ArchivedRecordsUiState(
                isLoading = false,
                archivedOwners = listOf(OwnerSummary(owner = owner(archived = true), accounts = emptyList())),
            ),
        )

        composeTestRule.onNodeWithText("归档时没有游戏账号").assertExists()
    }

    @Test
    fun independentlyArchivedAccountOfAnActiveOwnerIsRestorable() {
        setContent(
            ArchivedRecordsUiState(
                isLoading = false,
                archivedAccounts = listOf(
                    ArchivedAccount(
                        ownerId = "owner-1",
                        ownerNumber = "B001",
                        ownerNickname = "小满",
                        account = account(archived = true),
                    ),
                ),
            ),
        )

        composeTestRule.onNodeWithText("小满 · B001 · UID 10000628").assertExists()
        composeTestRule.onNodeWithText("恢复账号").assertExists()
    }
}
