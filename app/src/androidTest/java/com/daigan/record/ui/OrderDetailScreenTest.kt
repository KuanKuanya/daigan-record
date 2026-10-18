package com.daigan.record.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.feature.orders.OrderDetailContent
import com.daigan.record.testing.activeHostingDetailForUi
import com.daigan.record.testing.expiredHostingDetailForUi
import com.daigan.record.testing.shortOrderDetailForUi
import com.daigan.record.ui.theme.DaiganRecordTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrderDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val toggleCalls = mutableListOf<Triple<String, String, Boolean>>()
    private val progressCalls = mutableListOf<Triple<String, String, String>>()

    private fun setContent(detail: com.daigan.record.data.model.OrderDetail) {
        composeTestRule.setContent {
            DaiganRecordTheme {
                OrderDetailContent(
                    detail = detail,
                    lastBatchId = null,
                    undoResult = null,
                    onBack = {},
                    onToggleShortTask = { _, _ -> },
                    onUpdateShortProgress = { _, _ -> },
                    onCompleteShortOrder = {},
                    onToggleOccurrence = { taskId, occurrenceKey, completed ->
                        toggleCalls += Triple(taskId, occurrenceKey, completed)
                    },
                    onUpdateOccurrenceProgress = { taskId, occurrenceKey, value ->
                        progressCalls += Triple(taskId, occurrenceKey, value)
                    },
                    onCompleteHostingOrder = {},
                    onUndo = {},
                    onClearUndoResult = {},
                    onAddAttachment = { _, _, _ -> },
                    onReplaceAttachment = { _, _ -> },
                    onDeleteAttachment = {},
                    fileFor = { File("/does-not-exist") },
                    onBackfill = {},
                    onRenewClick = {},
                )
            }
        }
    }

    @Test
    fun hostingToggleCarriesTheDisplayedOccurrenceKey() {
        // An active period: an expired order's history is intentionally read-only, so it
        // has no toggle to carry an identity.
        setContent(activeHostingDetailForUi())

        composeTestRule.onNode(hasClickAction() and hasText("每日签到"))
            .performClick()

        assertEquals(
            listOf(Triple("task-hosting", "D:2026-10-17", true)),
            toggleCalls,
        )
    }

    @Test
    fun shortDetailShowsTasksPriceAndAPendingPaymentSection() {
        setContent(shortOrderDetailForUi())

        composeTestRule.onNodeWithText("推进主线").assertExists()
        composeTestRule.onNodeWithText("¥100.00").assertExists()
        composeTestRule.onNodeWithText("分批收款").assertExists()
        composeTestRule.onNodeWithText("整单完成").assertExists()
        composeTestRule.onNodeWithText("收款记录（第三阶段）").assertExists()
        composeTestRule.onNodeWithText("本版本不会创建首笔实收", substring = true).assertExists()
        composeTestRule.onNodeWithText("添加截图").assertExists()
    }

    @Test
    fun expiredHostingShowsRenewalAndHistoricalCorrection() {
        setContent(expiredHostingDetailForUi())

        assertTrue(composeTestRule.onAllNodesWithText("已到期 · 待续期").fetchSemanticsNodes().isNotEmpty())
        composeTestRule.onNodeWithText("续期").assertExists()
        composeTestRule.onNodeWithText("本期打卡记录").assertExists()
        composeTestRule.onNodeWithText("补录/更正").assertExists()
        composeTestRule.onNodeWithText("¥300.00").assertExists()
        composeTestRule.onNodeWithText("整单完成").assertDoesNotExist()
        composeTestRule.onNodeWithText("到期后不再生成新任务", substring = true).assertExists()
    }

    @Test
    fun longDetailContentStaysReachableAtDoubleFontScale() {
        val detail = shortOrderDetailForUi()
        val longTitle = "超长订单名称".repeat(8)
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = 2f),
            ) {
                DaiganRecordTheme {
                    OrderDetailContent(
                        detail = detail.copy(order = detail.order.copy(title = longTitle)),
                        lastBatchId = null,
                        undoResult = null,
                        onBack = {},
                        onToggleShortTask = { _, _ -> },
                        onUpdateShortProgress = { _, _ -> },
                        onCompleteShortOrder = {},
                        onToggleOccurrence = { taskId, occurrenceKey, completed ->
                            toggleCalls += Triple(taskId, occurrenceKey, completed)
                        },
                        onUpdateOccurrenceProgress = { taskId, occurrenceKey, value ->
                            progressCalls += Triple(taskId, occurrenceKey, value)
                        },
                        onCompleteHostingOrder = {},
                        onUndo = {},
                        onClearUndoResult = {},
                        onAddAttachment = { _, _, _ -> },
                        onReplaceAttachment = { _, _ -> },
                        onDeleteAttachment = {},
                        fileFor = { File("/does-not-exist") },
                        onBackfill = {},
                        onRenewClick = {},
                    )
                }
            }
        }
        composeTestRule.onNodeWithText(longTitle).assertExists()
        composeTestRule.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(androidx.compose.ui.test.hasText("整单完成"))
        composeTestRule.onNodeWithText("整单完成").assertExists()
    }
}
