package com.daigan.record.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.PaymentChannel
import com.daigan.record.data.model.Receipt
import com.daigan.record.data.model.ReceiptStatus
import com.daigan.record.feature.orders.OrderDetailContent
import com.daigan.record.testing.fixedInstant
import com.daigan.record.testing.shortOrderDetailForUi
import com.daigan.record.ui.theme.DaiganRecordTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CancelledReceiptUiTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun cancelledOrderKeepsExistingReceiptCorrectionsButDisablesNewPayments() {
        val base = shortOrderDetailForUi()
        val receipt = Receipt(
            id = "receipt-before-cancel", operationId = "payment-before-cancel",
            orderId = base.order.id, periodId = null, amountCents = 4000,
            receivedAt = fixedInstant, channel = PaymentChannel.WECHAT, note = null,
            status = ReceiptStatus.ACTIVE, replacedByReceiptId = null, createdAt = fixedInstant,
        )
        val detail = base.copy(order = base.order.copy(cancelledAt = fixedInstant), receipts = listOf(receipt))
        var amended: Receipt? = null
        var voided: Receipt? = null
        var newPayments = 0
        composeTestRule.setContent {
            DaiganRecordTheme {
                OrderDetailContent(
                    detail = detail, lastBatchId = null, undoResult = null, onBack = {},
                    onToggleShortTask = { _, _ -> }, onUpdateShortProgress = { _, _ -> },
                    onCompleteShortOrder = {}, onToggleOccurrence = { _, _, _ -> },
                    onUpdateOccurrenceProgress = { _, _, _ -> }, onCompleteHostingOrder = {},
                    onUndo = {}, onClearUndoResult = {}, onAddAttachment = { _, _, _ -> },
                    onReplaceAttachment = { _, _ -> }, onDeleteAttachment = {},
                    fileFor = { File("/does-not-exist") }, onBackfill = {}, onRenewClick = {},
                    onRecordPayment = { newPayments++ }, onAmendReceipt = { amended = it },
                    onVoidReceipt = { voided = it }, paymentInFlight = false,
                )
            }
        }
        val list = composeTestRule.onNodeWithTag("order-detail-list")
        val paymentButton = hasText("订单已作废") and hasClickAction()
        list.performScrollToNode(paymentButton)
        composeTestRule.onNode(paymentButton).assertIsNotEnabled()
        assertEquals(0, newPayments)
        list.performScrollToNode(hasText("更正"))
        composeTestRule.onNodeWithText("更正").assertIsEnabled().performClick()
        assertEquals(receipt, amended)
        list.performScrollToNode(hasText("作废", substring = false))
        composeTestRule.onNodeWithText("作废").assertIsEnabled().performClick()
        assertEquals(receipt, voided)
    }
}
