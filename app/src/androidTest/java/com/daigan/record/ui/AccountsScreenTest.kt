package com.daigan.record.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.OwnerSummary
import com.daigan.record.feature.accounts.AccountsScreen
import com.daigan.record.feature.accounts.OwnersUiState
import com.daigan.record.testing.account
import com.daigan.record.testing.owner
import com.daigan.record.ui.theme.DaiganRecordTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Layout tests for the owner list: empty state, grouping and large-font long text. */
@RunWith(AndroidJUnit4::class)
class AccountsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun emptyStateIsHonestAndHasOnlyTheOwnerCreationAction() {
        composeTestRule.setContent {
            DaiganRecordTheme {
                AccountsScreen(
                    uiState = OwnersUiState.Success(owners = emptyList(), archivedCount = 0),
                    onOpenSettings = {},
                    onOpenOwner = {},
                    onCreateOwner = {},
                    onOpenArchived = {},
                )
            }
        }

        composeTestRule.onNodeWithText("还没有老板资料").assertExists()
        composeTestRule.onNodeWithText("新增").assertExists()
        composeTestRule.onNodeWithText("归档记录").assertExists()
    }

    @Test
    fun ownerCardGroupsAccountsAndShowsTheImmutableNumber() {
        val summary = OwnerSummary(
            owner = owner(nickname = "小满"),
            accounts = listOf(
                account(id = "a1", gameName = "重返未来：1999"),
                account(id = "a2", gameName = "鸣潮", uid = "10002136"),
            ),
        )

        composeTestRule.setContent {
            DaiganRecordTheme {
                AccountsScreen(
                    uiState = OwnersUiState.Success(owners = listOf(summary), archivedCount = 2),
                    onOpenSettings = {},
                    onOpenOwner = {},
                    onCreateOwner = {},
                    onOpenArchived = {},
                )
            }
        }

        composeTestRule.onNodeWithText("小满").assertExists()
        composeTestRule.onNodeWithText("B001").assertExists()
        composeTestRule.onNodeWithText("2 个游戏账号 · 重返未来：1999 / 鸣潮").assertExists()
        composeTestRule.onNodeWithText("归档记录 (2)").assertExists()
    }

    @Test
    fun longChineseNicknameStaysReadableAtLargeFontScale() {
        val longNickname = "超长老板昵称".repeat(5) // 30 Chinese characters
        val summary = OwnerSummary(
            owner = owner(nickname = longNickname),
            accounts = listOf(
                account(gameName = "重返未来：1999".repeat(3), uid = "1000062810000628"),
            ),
        )

        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = 2f),
            ) {
                DaiganRecordTheme {
                    AccountsScreen(
                        uiState = OwnersUiState.Success(listOf(summary), archivedCount = 0),
                        onOpenSettings = {},
                        onOpenOwner = {},
                        onCreateOwner = {},
                        onOpenArchived = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText(longNickname).assertExists()
        composeTestRule.onNodeWithText("新增").assertExists()
        composeTestRule.onNodeWithText("查看账号").assertExists()
    }
}
