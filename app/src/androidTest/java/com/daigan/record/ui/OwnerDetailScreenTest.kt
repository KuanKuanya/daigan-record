package com.daigan.record.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.feature.accounts.OwnerDetailContent
import com.daigan.record.testing.account
import com.daigan.record.testing.owner
import com.daigan.record.testing.ownerDetail
import com.daigan.record.ui.theme.DaiganRecordTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Owner detail layout tests: credentials are viewable/copyable without an extra gate,
 * the order entry is visibly unavailable, and long text survives enlarged fonts.
 */
@RunWith(AndroidJUnit4::class)
class OwnerDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setDetailContent(
        detail: com.daigan.record.data.model.OwnerDetail = ownerDetail(),
    ) {
        composeTestRule.setContent {
            DaiganRecordTheme {
                OwnerDetailContent(
                    detail = detail,
                    onBack = {},
                    onEditOwner = {},
                    onAddAccount = {},
                    onEditAccount = { _, _ -> },
                    onCopy = {},
                    onArchiveOwner = {},
                    onRestoreOwner = {},
                    onArchiveAccount = {},
                    onRestoreAccount = {},
                )
            }
        }
    }

    @Test
    fun passwordCredentialsAreHiddenUntilRevealedAndCanBeCopied() {
        setDetailContent()

        composeTestRule.onNodeWithText("登录信息 · 账号密码").performClick()
        composeTestRule.onNodeWithText("demo-xiaoman").assertExists()
        composeTestRule.onNodeWithText("••••••").assertExists()
        composeTestRule.onNodeWithContentDescription("复制密码").assertExists()

        composeTestRule.onNodeWithContentDescription("显示密码").performClick()

        composeTestRule.onNodeWithText("demo1999").assertExists()
    }

    @Test
    fun verificationCodeAccountsNeverShowAStoredPassword() {
        setDetailContent(
            ownerDetail(
                activeAccounts = listOf(
                    account(
                        id = "account-code",
                        loginMethod = LoginMethod.VERIFICATION_CODE,
                        password = null,
                    ),
                ),
            ),
        )

        composeTestRule.onNodeWithText("登录信息 · 验证码登录").performClick()

        composeTestRule.onNodeWithText("使用验证码登录，不保存验证码。").assertExists()
        composeTestRule.onNodeWithContentDescription("复制密码").assertDoesNotExist()
    }

    @Test
    fun orderEntryIsPresentButClearlyUnavailable() {
        setDetailContent()

        composeTestRule.onNodeWithText("为这个账号接新单").assertIsNotEnabled()
        composeTestRule.onNodeWithText("订单功能将在第二阶段提供，当前入口不可用。").assertExists()
        composeTestRule.onNodeWithText("暂无订单").assertExists()
        composeTestRule.onNodeWithText("B001").assertExists()
    }

    @Test
    fun longGameNameAndLargeFontDoNotHideActions() {
        val longGameName = "重返未来：1999".repeat(4)
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = 2f),
            ) {
                DaiganRecordTheme {
                    OwnerDetailContent(
                        detail = ownerDetail(
                            owner = owner(nickname = "超长老板昵称".repeat(5)),
                            activeAccounts = listOf(account(gameName = longGameName)),
                        ),
                        onBack = {},
                        onEditOwner = {},
                        onAddAccount = {},
                        onEditAccount = { _, _ -> },
                        onCopy = {},
                        onArchiveOwner = {},
                        onRestoreOwner = {},
                        onArchiveAccount = {},
                        onRestoreAccount = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText(longGameName).assertExists()
        composeTestRule.onNodeWithText("新增账号").assertExists()
        composeTestRule.onNodeWithText("编辑账号").assertExists()
        composeTestRule.onNodeWithText("归档账号").assertExists()
    }

    @Test
    fun archivedOwnerShowsRestoreInsteadOfEditing() {
        setDetailContent(
            ownerDetail(
                owner = owner(archived = true),
                activeAccounts = emptyList(),
                archivedAccounts = listOf(account(archived = true)),
            ),
        )

        composeTestRule.onNodeWithContentDescription("恢复老板").assertExists()
        composeTestRule.onNodeWithText("恢复").assertExists()
        composeTestRule.onNodeWithText("新增账号").assertDoesNotExist()
    }
}
