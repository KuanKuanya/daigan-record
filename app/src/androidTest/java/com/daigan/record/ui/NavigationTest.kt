package com.daigan.record.ui

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
import com.daigan.record.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
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
    }

    @Test
    fun todayIsTheDefaultDestinationWithBothTabs() {
        composeTestRule.onNodeWithText("托管打卡").assertExists()
        composeTestRule.onNodeWithText("短期任务").assertExists()
        composeTestRule.onNodeWithText("还没有托管打卡").assertExists()
        composeTestRule.onNode(bottomNav("账号信息")).assertExists()
        composeTestRule.onNode(bottomNav("数据统计")).assertExists()
    }

    @Test
    fun switchingToShortTaskTabShowsItsOwnEmptyState() {
        composeTestRule.onNodeWithText("短期任务").performClick()

        composeTestRule.onNodeWithText("还没有短期任务").assertExists()
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

    @Test
    fun settingsEntryShowsHonestPendingStateAndKeepsBackNavigation() {
        openAccounts()
        composeTestRule.onNodeWithContentDescription("设置").performClick()
        composeTestRule.onNodeWithText("游戏模板").performClick()

        composeTestRule.onNodeWithText("功能尚未提供").assertExists()
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
        composeTestRule.onNodeWithText("含历史欠款", substring = true).assertExists()
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
        composeTestRule.onNodeWithText("还没有收款记录").assertExists()
        // Independent page: no bottom navigation.
        composeTestRule.onAllNodesWithText("今日事项").assertCountEquals(0)

        composeTestRule.onNodeWithContentDescription("返回").performClick()
        composeTestRule.onNodeWithText("备份与恢复").assertExists()
    }

    private fun openAccounts() {
        composeTestRule.onNode(bottomNav("账号信息")).performClick()
    }

    /** Bottom navigation items are the only clickable nodes that carry these labels. */
    private fun bottomNav(label: String) = hasText(label) and hasClickAction()
}
