package com.kuankuan.daigan.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kuankuan.daigan.testing.awaitApplicationReady
import com.kuankuan.daigan.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Navigation and honest empty state tests. The Hilt test module swaps in an empty
 * in-memory database, so any fake seed data would become visible here.
 */
@HiltAndroidTest
class NavigationTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
        composeTestRule.awaitApplicationReady()
    }

    @Test
    fun todayIsTheDefaultDestinationWithBothTabs() {
        waitForText("还没有托管打卡")
        composeTestRule.onNodeWithText("托管打卡").assertExists()
        composeTestRule.onNodeWithText("短期任务").assertExists()
        composeTestRule.onNodeWithText("还没有托管打卡").assertExists()
        composeTestRule.onNode(bottomNav("账号信息")).assertExists()
        composeTestRule.onNode(bottomNav("数据统计")).assertExists()
    }

    @Test
    fun switchingToShortTaskTabShowsItsOwnEmptyState() {
        // The list is backed by asynchronous database flows: before the first snapshot
        // the tab content is a loading indicator. Wait for the condition instead of
        // assuming the snapshot arrived in the same frame as the click.
        waitForText("还没有托管打卡")
        composeTestRule.onNodeWithText("短期任务").performClick()

        waitForText("还没有短期任务")
        composeTestRule.onNodeWithText("还没有短期任务").assertExists()
        // The other tab's content must not be shown at the same time.
        composeTestRule.onAllNodesWithText("还没有托管打卡").assertCountEquals(0)
    }

    @Test
    fun accountsScreenHasOnlyTheOwnerCreationActionAndEmptyState() {
        openAccounts()

        composeTestRule.onNodeWithText("新增").assertExists()
        composeTestRule.onNodeWithText("还没有老板资料").assertExists()
        composeTestRule.onNodeWithText("归档记录").assertExists()
        // No global order creation entry anywhere in this phase.
        composeTestRule.onAllNodesWithText("新增订单").assertCountEquals(0)
    }

    @Test
    fun settingsIsAnIndependentScreenWithoutBottomNavigation() {
        openAccounts()
        composeTestRule.onNodeWithContentDescription("设置").performClick()

        composeTestRule.onNodeWithText("收款明细").assertExists()
        composeTestRule.onNodeWithText("游戏模板").assertExists()
        composeTestRule.onNodeWithText("提醒设置").assertExists()
        composeTestRule.onNodeWithText("备份与恢复").assertExists()
        // Bottom navigation is hidden on the settings destination.
        composeTestRule.onAllNodesWithText("数据统计").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("今日事项").assertCountEquals(0)
    }

    /**
     * The template library is an independent settings page with working back navigation.
     */
    @Test
    fun settingsTemplateEntryOpensTheRealLibraryAndKeepsBackNavigation() {
        openAccounts()
        composeTestRule.onNodeWithContentDescription("设置").performClick()
        composeTestRule.onNodeWithText("游戏模板").performClick()

        composeTestRule.onNodeWithTag("template-library").assertExists()
        composeTestRule.onNodeWithTag("template-library-new").assertExists()
        composeTestRule.onNodeWithTag("template-library-import").assertExists()
        // The generic starter is seeded by the library itself.
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("通用模板")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeTestRule.onAllNodesWithText("重返未来：1999").fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("鸣潮").fetchSemanticsNodes().isEmpty())
        composeTestRule.onAllNodesWithText("数据统计").assertCountEquals(0)

        composeTestRule.onNodeWithContentDescription("返回").performClick()
        composeTestRule.onNodeWithText("备份与恢复").assertExists()

        composeTestRule.onNodeWithContentDescription("返回").performClick()
        composeTestRule.onNodeWithText("新增").assertExists()
        composeTestRule.onNode(bottomNav("数据统计")).assertExists()
    }

    @Test
    fun statsScreenShowsRealZeroesInsteadOfInventedNumbers() {
        composeTestRule.onNode(bottomNav("数据统计")).performClick()

        // The income label is calendar-month specific ("2026 年 10 月实收") and the card
        // only exists once the first real snapshot has loaded, so wait for the card
        // instead of asserting against the loading indicator.
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithTag("stats-income-label")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("stats-income-label")
            .assertTextContains("实收", substring = true)
        composeTestRule.onNodeWithTag("stats-income-label")
            .assertTextContains("月", substring = true)
        composeTestRule.onNodeWithText("全部待收").assertExists()
        composeTestRule.onNodeWithText("含历史欠款", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("全部收齐").assertExists()
        // Two honest zero amounts: this month's receipts and all-time debt.
        composeTestRule.onAllNodesWithText("¥0.00").assertCountEquals(2)
    }

    @Test
    fun receiptHistoryIsIndependentUnderSettingsAndStartsEmpty() {
        openAccounts()
        composeTestRule.onNodeWithContentDescription("设置").performClick()
        composeTestRule.onNodeWithText("收款明细").performClick()

        composeTestRule.onNodeWithText("全部实收流水", substring = true).assertExists()
        // The empty state appears only after the first database snapshot; wait for the
        // condition instead of assuming the flow emitted in the same frame as the tap.
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("还没有收款记录")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("还没有收款记录").assertExists()
        // Independent page: no bottom navigation.
        composeTestRule.onAllNodesWithText("今日事项").assertCountEquals(0)

        composeTestRule.onNodeWithContentDescription("返回").performClick()
        composeTestRule.onNodeWithText("备份与恢复").assertExists()
    }

    private fun openAccounts() {
        composeTestRule.onNode(bottomNav("账号信息")).performClick()
    }

    /**
     * Condition-based readiness: the Today lists only exist after the first database
     * snapshot, so asserting immediately after a tap races the flow collection.
     */
    private fun waitForText(text: String) {
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Bottom navigation items are the only clickable nodes that carry these labels. */
    private fun bottomNav(label: String) = hasText(label) and hasClickAction()
}
