package com.kuankuan.daigan.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kuankuan.daigan.data.model.LoginMethod
import com.kuankuan.daigan.feature.accounts.OwnerDetailContent
import com.kuankuan.daigan.testing.account
import com.kuankuan.daigan.testing.owner
import com.kuankuan.daigan.testing.ownerDetail
import com.kuankuan.daigan.testing.shortOrderSummary
import com.kuankuan.daigan.ui.theme.DaiganHandbookTheme
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
        detail: com.kuankuan.daigan.data.model.OwnerDetail = ownerDetail(),
        ordersByAccount: Map<String, List<com.kuankuan.daigan.data.model.OrderSummary>> = emptyMap(),
        onCreateOrder: (String) -> Unit = {},
        onOpenOrder: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            DaiganHandbookTheme {
                OwnerDetailContent(
                    detail = detail,
                    ordersByAccount = ordersByAccount,
                    onBack = {},
                    onEditOwner = {},
                    onAddAccount = {},
                    onEditAccount = { _, _ -> },
                    onCopy = {},
                    onArchiveOwner = {},
                    onRestoreOwner = {},
                    onArchiveAccount = {},
                    onRestoreAccount = {},
                    onCreateOrder = onCreateOrder,
                    onOpenOrder = onOpenOrder,
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
    fun orderEntryBelongsToTheAccountAndOpensItsCreationFlow() {
        var createdFor: String? = null
        setDetailContent(onCreateOrder = { createdFor = it })

        composeTestRule.onNodeWithText("为这个账号接新单").assertIsEnabled().performClick()
        org.junit.Assert.assertEquals("account-1", createdFor)
        composeTestRule.onNodeWithText("该账号暂无订单。").assertExists()
        composeTestRule.onNodeWithText("B001").assertExists()
    }

    @Test
    fun accountListsItsOrdersAndTheyRemainOpenable() {
        var opened: String? = null
        setDetailContent(
            ordersByAccount = mapOf("account-1" to listOf(shortOrderSummary())),
            onOpenOrder = { opened = it },
        )

        composeTestRule.onNodeWithText("订单 · 1").assertExists()
        composeTestRule.onNodeWithText("短期任务").performClick()
        org.junit.Assert.assertEquals("order-1", opened)
    }

    @Test
    fun cancelledOrderShowsItsStatusAndRemainsOpenable() {
        var opened: String? = null
        val summary = shortOrderSummary()
        setDetailContent(
            ordersByAccount = mapOf(
                "account-1" to listOf(summary.copy(order = summary.order.copy(cancelledAt = com.kuankuan.daigan.testing.fixedInstant))),
            ),
            onOpenOrder = { opened = it },
        )

        composeTestRule.onNodeWithText("已作废").assertExists()
        composeTestRule.onNodeWithText("进行中").assertDoesNotExist()
        composeTestRule.onNodeWithText("短期任务").performClick()
        org.junit.Assert.assertEquals("order-1", opened)
    }

    @Test
    fun longGameNameAndLargeFontDoNotHideActions() {
        val longGameName = "重返未来：1999".repeat(4)
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = 2f),
            ) {
                DaiganHandbookTheme {
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

    /**
     * An archived owner may only be restored as a whole. Its accounts stay viewable but
     * offer no enabled action: no add, no edit, no separate archive and no separate restore.
     */
    @Test
    fun archivedOwnerOffersNoAccountActionsButKeepsTheAccountsReadable() {
        setDetailContent(
            ownerDetail(
                owner = owner(archived = true),
                activeAccounts = emptyList(),
                archivedAccounts = listOf(account(archived = true)),
            ),
        )

        // Restoring the owner remains available.
        composeTestRule.onNodeWithContentDescription("恢复老板").assertExists()

        composeTestRule.onNodeWithText("已归档账号 · 1 个").performScrollTo().performClick()

        // Credentials of the archived account stay viewable.
        composeTestRule.onNodeWithText("重返未来：1999").assertExists()
        composeTestRule.onNodeWithText("登录信息 · 账号密码").performClick()
        composeTestRule.onNodeWithText("demo-xiaoman").assertExists()

        // No enabled account action is offered while the parent is archived.
        composeTestRule.onNodeWithText("恢复").assertDoesNotExist()
        composeTestRule.onNodeWithText("新增账号").assertDoesNotExist()
        composeTestRule.onNodeWithText("编辑账号").assertDoesNotExist()
        composeTestRule.onNodeWithText("归档账号").assertDoesNotExist()
        composeTestRule.onNodeWithText("恢复老板后可单独恢复此账号。").assertExists()
    }

    /** With an active owner an archived account can be restored individually. */
    @Test
    fun activeOwnerCanRestoreAnIndividuallyArchivedAccount() {
        setDetailContent(
            ownerDetail(
                owner = owner(archived = false),
                activeAccounts = emptyList(),
                archivedAccounts = listOf(account(archived = true)),
            ),
        )

        composeTestRule.onNodeWithText("已归档账号 · 1 个").assertExists()
        composeTestRule.onNodeWithText("恢复").assertDoesNotExist()
        composeTestRule.onNodeWithText("已归档账号 · 1 个").performScrollTo().performClick()
        composeTestRule.onNodeWithText("恢复").assertExists()
    }

    @Test
    fun archivedAccountsStartCollapsedAndCanBeViewedRestoredAndCollapsedAgain() {
        var restored: String? = null
        composeTestRule.setContent {
            DaiganHandbookTheme {
                OwnerDetailContent(
                    detail = ownerDetail(
                        activeAccounts = emptyList(),
                        archivedAccounts = listOf(account(archived = true)),
                    ),
                    onBack = {},
                    onEditOwner = {},
                    onAddAccount = {},
                    onEditAccount = { _, _ -> },
                    onCopy = {},
                    onArchiveOwner = {},
                    onRestoreOwner = {},
                    onArchiveAccount = {},
                    onRestoreAccount = { restored = it },
                )
            }
        }
        composeTestRule.onNodeWithText("已归档账号 · 1 个").assertExists()
        composeTestRule.onNodeWithText("重返未来：1999").assertDoesNotExist()
        composeTestRule.onNodeWithText("已归档账号 · 1 个").performScrollTo().performClick()
        composeTestRule.onNodeWithText("重返未来：1999").assertExists()
        composeTestRule.onNodeWithText("登录信息 · 账号密码").performClick()
        composeTestRule.onNodeWithText("demo-xiaoman").assertExists()
        composeTestRule.onNodeWithText("恢复").performScrollTo().performClick()
        org.junit.Assert.assertEquals("account-1", restored)
        composeTestRule.onNodeWithText("已归档账号 · 1 个").performScrollTo().performClick()
        composeTestRule.onNodeWithText("重返未来：1999").assertDoesNotExist()
    }

    @Test
    fun archivedAccountExpansionSurvivesStateRestorationAndResetsForAnotherOwner() {
        val restoration = StateRestorationTester(composeTestRule)
        val selectedOwner = androidx.compose.runtime.mutableStateOf("owner-1")
        restoration.setContent {
            DaiganHandbookTheme {
                OwnerDetailContent(
                    detail = ownerDetail(
                        owner = owner(id = selectedOwner.value),
                        activeAccounts = emptyList(),
                        archivedAccounts = listOf(account(ownerId = selectedOwner.value, archived = true)),
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
        composeTestRule.onNodeWithText("已归档账号 · 1 个").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeTestRule.onNodeWithText("重返未来：1999").assertExists()
        composeTestRule.runOnIdle { selectedOwner.value = "owner-2" }
        composeTestRule.onNodeWithText("已归档账号 · 1 个").assertExists()
        composeTestRule.onNodeWithText("重返未来：1999").assertDoesNotExist()
    }
}
