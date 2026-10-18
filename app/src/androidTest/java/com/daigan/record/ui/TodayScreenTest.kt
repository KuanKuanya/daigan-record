package com.daigan.record.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.core.time.HostingStatus
import com.daigan.record.feature.today.TodayScreen
import com.daigan.record.feature.today.TodayTab
import com.daigan.record.feature.today.TodayUiState
import com.daigan.record.testing.hostingOrderForToday
import com.daigan.record.testing.shortWorkOrderForToday
import com.daigan.record.ui.theme.DaiganRecordTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodayScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(
        state: TodayUiState,
        onOpenOrder: (String) -> Unit = {},
        onCompleteAll: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            DaiganRecordTheme {
                TodayScreen(
                    uiState = state,
                    onSelectTab = {},
                    onOpenSettings = {},
                    onOpenOrder = onOpenOrder,
                    onCompleteAll = onCompleteAll,
                    onCompleteOrder = {},
                    onToggleHostingTask = { taskId, occurrenceKey, completed ->
                        toggleCalls += Triple(taskId, occurrenceKey, completed)
                    },
                    onUpdateHostingProgress = { taskId, occurrenceKey, value ->
                        progressCalls += Triple(taskId, occurrenceKey, value)
                    },
                    onToggleShortTask = { _, _ -> },
                    onUpdateShortProgress = { _, _ -> },
                    onUndo = {},
                    onClearUndo = {},
                    onToggleCompleted = {},
                )
            }
        }
    }

    private val toggleCalls = mutableListOf<Triple<String, String, Boolean>>()
    private val progressCalls = mutableListOf<Triple<String, String, String>>()

    @Test
    fun hostingToggleCarriesTheDisplayedOccurrenceKey() {
        setContent(
            TodayUiState(
                dateLabel = "10 月 17 日",
                loading = false,
                hosting = listOf(hostingOrderForToday()),
            ),
        )
        composeTestRule.onNode(hasClickAction() and hasText("每日签到"))
            .performClick()

        assertEquals(
            listOf(Triple("task-1", "D:2026-10-17", true)),
            toggleCalls,
        )
    }

    @Test
    fun emptyHostingStatePointsAtTheAccountContext() {
        setContent(TodayUiState(dateLabel = "10 月 17 日", loading = false))
        composeTestRule.onNodeWithText("还没有托管打卡").assertExists()
    }

    @Test
    fun emptyShortTaskStatePointsAtTheAccountContext() {
        setContent(
            TodayUiState(
                dateLabel = "10 月 17 日",
                loading = false,
                selectedTab = TodayTab.SHORT_TASK,
            ),
        )
        composeTestRule.onNodeWithText("还没有短期任务").assertExists()
    }

    @Test
    fun activeOrdersShowTasksWhileCompletedOrdersStayCollapsed() {
        val active = hostingOrderForToday(orderId = "active")
        val completed = hostingOrderForToday(
            orderId = "completed",
            title = "已完成的单",
            allCompleted = true,
        )
        setContent(
            TodayUiState(
                dateLabel = "10 月 17 日",
                loading = false,
                hosting = listOf(active, completed),
            ),
        )

        composeTestRule.onNodeWithText("日常托管").assertExists()
        composeTestRule.onNodeWithText("每日签到").assertExists()
        composeTestRule.onNodeWithText("整单完成").assertExists()
        composeTestRule.onNodeWithText("已完成 · 1 单").assertExists()
        composeTestRule.onNodeWithText("已完成的单").assertDoesNotExist()
    }

    @Test
    fun expandedCompletedSectionShowsCompletedCards() {
        val completed = hostingOrderForToday(
            orderId = "completed",
            title = "已完成的单",
            allCompleted = true,
        )
        setContent(
            TodayUiState(
                dateLabel = "10 月 17 日",
                loading = false,
                hosting = listOf(completed),
                completedExpanded = true,
            ),
        )
        composeTestRule.onNodeWithText("已完成 · 1 单").assertExists()
        composeTestRule.onNodeWithText("已完成的单").assertExists()
    }

    @Test
    fun expiredOrdersAreClearlyPendingRenewalAndDoNotOfferBulkCompletion() {
        val expired = hostingOrderForToday(status = HostingStatus.EXPIRED)
        setContent(
            TodayUiState(
                dateLabel = "10 月 17 日",
                loading = false,
                hosting = listOf(expired),
            ),
        )
        composeTestRule.onNodeWithText("已到期 · 待续期").assertExists()
        composeTestRule.onNodeWithText("查看并续期").assertExists()
        composeTestRule.onNodeWithText("整单完成").assertDoesNotExist()
    }

    @Test
    fun shortTasksShowDeadlineStatusAndAmount() {
        setContent(
            TodayUiState(
                dateLabel = "10 月 17 日",
                loading = false,
                selectedTab = TodayTab.SHORT_TASK,
                shortOrders = listOf(shortWorkOrderForToday()),
            ),
        )
        composeTestRule.onNodeWithText("已逾期 2 天 · 2026.10.15 截止").assertExists()
        composeTestRule.onNodeWithText("约定总价 ¥100.00").assertExists()
        composeTestRule.onNodeWithText("订单详情").assertExists()
    }

    @Test
    fun longOrderContentStaysReachableAtDoubleFontScale() {
        val longTitle = "超长托管订单名称".repeat(6)
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = 2f),
            ) {
                DaiganRecordTheme {
                    TodayScreen(
                        uiState = TodayUiState(
                            dateLabel = "10 月 17 日",
                            loading = false,
                            hosting = listOf(
                                hostingOrderForToday(title = longTitle, taskTitle = "超长任务".repeat(8)),
                            ),
                        ),
                        onSelectTab = {},
                        onOpenSettings = {},
                        onOpenOrder = {},
                        onCompleteAll = {},
                        onCompleteOrder = {},
                        onToggleHostingTask = { taskId, occurrenceKey, completed ->
                            toggleCalls += Triple(taskId, occurrenceKey, completed)
                        },
                        onUpdateHostingProgress = { taskId, occurrenceKey, value ->
                            progressCalls += Triple(taskId, occurrenceKey, value)
                        },
                        onToggleShortTask = { _, _ -> },
                        onUpdateShortProgress = { _, _ -> },
                        onUndo = {},
                        onClearUndo = {},
                        onToggleCompleted = {},
                    )
                }
            }
        }
        composeTestRule.onNodeWithText(longTitle).assertExists()
        composeTestRule.onNodeWithText("整单完成").assertExists()
    }
}
