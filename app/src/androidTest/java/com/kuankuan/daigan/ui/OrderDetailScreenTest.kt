package com.kuankuan.daigan.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kuankuan.daigan.feature.orders.OrderDetailContent
import com.kuankuan.daigan.testing.activeHostingDetailForUi
import com.kuankuan.daigan.testing.expiredHostingDetailForUi
import com.kuankuan.daigan.testing.shortOrderDetailForUi
import com.kuankuan.daigan.ui.theme.DaiganHandbookTheme
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
    private val calendarCalls = mutableListOf<List<Any>>()
    private val progressCalls = mutableListOf<Triple<String, String, String>>()

    private fun setContent(detail: com.kuankuan.daigan.data.model.OrderDetail) {
        composeTestRule.setContent {
            DaiganHandbookTheme {
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
                    onCalendarCompletion = { period, task, date, key, completed ->
                        calendarCalls += listOf(period, task, date, key, completed)
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
                    onRecordPayment = {},
                    onAmendReceipt = {},
                    onVoidReceipt = {},
                    paymentInFlight = false,
                )
            }
        }
    }

    @Test
    fun headerManagementExposesDateCorrectionAndCleanup() {
        setContent(shortOrderDetailForUi())
        composeTestRule.onNodeWithText("修改日期").assertDoesNotExist()
        composeTestRule.onNodeWithText("管理").performClick()
        composeTestRule.onNodeWithText("修改日期").assertExists()
        composeTestRule.onNodeWithText("删除误建订单").assertExists()
        composeTestRule.onNodeWithText("作废订单").assertExists()
    }

    @Test
    fun hostingCalendarToggleCarriesTheDisplayedOccurrenceKey() {
        // An active period: an expired order's history is intentionally read-only, so it
        // has no toggle to carry an identity.
        setContent(activeHostingDetailForUi())

        composeTestRule.onNode(hasClickAction() and hasText("每日签到"))
            .performClick()

        assertEquals("D:2026-10-17", calendarCalls.single()[3])
        assertTrue(toggleCalls.isEmpty())
    }

    @Test
    fun hostingCalendarShowsSelectedGameDayAndUsesItsOccurrenceKey() {
        setContent(activeHostingDetailForUi())

        composeTestRule.onNodeWithTag("hosting-calendar-day-2026-10-17").assertExists()
        composeTestRule.onNodeWithText("2026-10-17 · 0/1").assertExists()
        composeTestRule.onNode(
            hasClickAction() and hasText("每日签到") and hasAnyAncestor(hasTestTag("hosting-calendar")),
        ).performClick()

        assertEquals("D:2026-10-17", calendarCalls.single()[3])
        assertTrue(toggleCalls.isEmpty())
    }

    @Test
    fun calendarPastDateUsesHistoricalCompletionInsteadOfTodayWrite() {
        val detail = activeHostingDetailForUi()
        setContent(detail)
        val date = detail.today.minusDays(1)
        composeTestRule.onNodeWithTag("hosting-calendar-day-$date").performClick()
        composeTestRule.onNode(
            hasClickAction() and hasText("每日签到") and hasAnyAncestor(hasTestTag("hosting-calendar")),
        ).performClick()
        assertEquals(
            listOf(detail.currentPeriod!!.id, "task-hosting", date, "D:$date", true),
            calendarCalls.single(),
        )
        assertTrue(toggleCalls.isEmpty())
    }

    @Test
    fun hostingCalendarRemainsReadableAtOnePointFourFontScale() {
        val detail = activeHostingDetailForUi()
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = 1.4f),
            ) {
                DaiganHandbookTheme {
                    OrderDetailContent(
                        detail = detail,
                        lastBatchId = null,
                        undoResult = null,
                        onBack = {},
                        onToggleShortTask = { _, _ -> },
                        onUpdateShortProgress = { _, _ -> },
                        onCompleteShortOrder = {},
                        onToggleOccurrence = { _, _, _ -> },
                        onUpdateOccurrenceProgress = { _, _, _ -> },
                        onCompleteHostingOrder = {},
                        onUndo = {},
                        onClearUndoResult = {},
                        onAddAttachment = { _, _, _ -> },
                        onReplaceAttachment = { _, _ -> },
                        onDeleteAttachment = {},
                        fileFor = { File("/does-not-exist") },
                        onBackfill = {},
                        onRenewClick = {},
                        onRecordPayment = {},
                        onAmendReceipt = {},
                        onVoidReceipt = {},
                        paymentInFlight = false,
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("hosting-calendar").assertExists()
        composeTestRule.onNodeWithTag("order-detail-list").performScrollToNode(hasText("收款与欠款"))
        composeTestRule.onNodeWithText("收款与欠款").assertExists()
    }

    @Test
    fun emptyHostingHistoryExplainsBatchCompletionDoesNotCreatePastRecords() {
        setContent(activeHostingDetailForUi())

        composeTestRule.onNodeWithTag("order-detail-list")
            .performScrollToNode(hasText("本期打卡记录"))
        composeTestRule.onNodeWithText("批量完成不会补写历史遗漏", substring = true).assertExists()
    }

    @Test
    fun shortDetailShowsTasksPriceAndARealPaymentSection() {
        setContent(shortOrderDetailForUi())

        composeTestRule.onNodeWithText("推进主线").assertExists()
        composeTestRule.onNodeWithText("¥100.00").assertExists()
        // "分批收款" appears in both the order summary and the financial card. Scope the
        // checks to those regions instead of deleting the assertion or accepting any one.
        composeTestRule.onNode(
            hasText("分批收款") and hasAnyAncestor(hasTestTag("order-detail-summary")),
        ).assertExists()
        composeTestRule.onNode(
            hasText("分批收款") and hasAnyAncestor(hasTestTag("order-detail-payment-section")),
        ).assertExists()
        composeTestRule.onNodeWithText("整单完成").assertExists()
        composeTestRule.onNodeWithText("收款与欠款").assertExists()
        composeTestRule.onNodeWithText("¥100.00 / ¥0.00 / ¥100.00").assertExists()
        composeTestRule.onNodeWithText("记录收款").assertExists()
        composeTestRule.onNodeWithText("还没有收款记录", substring = true).assertExists()
        composeTestRule.onNodeWithText("添加截图").assertExists()
    }

    @Test
    fun expiredHostingShowsRenewalAndHistoricalCorrection() {
        setContent(expiredHostingDetailForUi())

        assertTrue(composeTestRule.onAllNodesWithText("已到期 · 待续期").fetchSemanticsNodes().isNotEmpty())
        // The price is shown in the order summary and the outstanding total is a second
        // identical amount. Both are real; assert each in its own region.
        composeTestRule.onNode(
            hasText("¥300.00") and hasAnyAncestor(hasTestTag("order-detail-summary")),
        ).assertExists()

        composeTestRule.onNodeWithTag("order-detail-list").performScrollToNode(hasText("续期"))
        composeTestRule.onNodeWithText("续期").assertExists()
        composeTestRule.onNodeWithText("本期打卡记录").assertExists()
        composeTestRule.onNodeWithText("补录/更正").assertExists()
        composeTestRule.onNodeWithText("¥300.00 / ¥0.00 / ¥300.00").assertExists()
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
                DaiganHandbookTheme {
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
                        onRecordPayment = {},
                        onAmendReceipt = {},
                        onVoidReceipt = {},
                        paymentInFlight = false,
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
