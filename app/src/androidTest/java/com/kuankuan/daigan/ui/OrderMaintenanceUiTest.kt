package com.kuankuan.daigan.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kuankuan.daigan.data.model.OrderDetail
import com.kuankuan.daigan.feature.orders.OrderMaintenanceActions
import com.kuankuan.daigan.testing.fixedInstant
import com.kuankuan.daigan.testing.shortOrderDetailForUi
import com.kuankuan.daigan.ui.theme.DaiganHandbookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrderMaintenanceUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun showActions(
        detail: OrderDetail = shortOrderDetailForUi(),
        busy: Boolean = false,
        onEdit: () -> Unit = {},
        onDelete: () -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            DaiganHandbookTheme {
                OrderMaintenanceActions(
                    detail = detail,
                    onEdit = onEdit,
                    onDelete = onDelete,
                    onCancel = onCancel,
                    busy = busy,
                )
            }
        }
    }

    @Test
    fun editingDatesOpensTheEditor() {
        var edits = 0
        showActions(onEdit = { edits++ })
        composeTestRule.onNodeWithText("修改日期").assertIsEnabled().performClick()
        assertEquals(1, edits)
    }

    @Test
    fun deletingAMistakenOrderRequiresConfirmationAndCanBeDismissed() {
        var deletes = 0
        showActions(onDelete = { deletes++ })
        composeTestRule.onNodeWithText("删除误建订单").performClick()
        assertEquals(0, deletes)
        composeTestRule.onNodeWithText("取消").performClick()
        assertEquals(0, deletes)
        composeTestRule.onNodeWithText("删除误建订单").performClick()
        composeTestRule.onNodeWithText("确认删除").performClick()
        assertEquals(1, deletes)
    }

    @Test
    fun cancellationRequiresConfirmationAndCanBeDismissed() {
        var cancellations = 0
        showActions(onCancel = { cancellations++ })
        composeTestRule.onNodeWithText("作废订单").performClick()
        assertEquals(0, cancellations)
        composeTestRule.onNodeWithText("取消").performClick()
        assertEquals(0, cancellations)
        composeTestRule.onNodeWithText("作废订单").performClick()
        composeTestRule.onNodeWithText("确认作废").performClick()
        assertEquals(1, cancellations)
    }

    @Test
    fun recordedProgressPreventsDeletionButStillAllowsCancellation() {
        val detail = shortOrderDetailForUi()
        showActions(detail.copy(tasks = detail.tasks.map { it.copy(completedAt = fixedInstant) }))
        composeTestRule.onNodeWithText("删除误建订单").assertDoesNotExist()
        composeTestRule.onNodeWithText("作废订单").assertIsEnabled()
    }

    @Test
    fun cancelledOrderExplainsItsReadOnlyStateAndOffersNoMaintenanceActions() {
        val detail = shortOrderDetailForUi()
        showActions(detail.copy(order = detail.order.copy(cancelledAt = fixedInstant)))
        composeTestRule.onNodeWithText("订单已作废").assertExists()
        composeTestRule.onNodeWithText("修改日期").assertDoesNotExist()
        composeTestRule.onNodeWithText("删除误建订单").assertDoesNotExist()
        composeTestRule.onNodeWithText("作废订单").assertDoesNotExist()
    }

    @Test
    fun pendingOperationDisablesAllMaintenanceActions() {
        showActions(busy = true)
        composeTestRule.onNodeWithText("修改日期").assertIsNotEnabled()
        composeTestRule.onNodeWithText("删除误建订单").assertIsNotEnabled()
        composeTestRule.onNodeWithText("作废订单").assertIsNotEnabled()
    }
}
