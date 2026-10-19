package com.daigan.record.feature.orders

import com.daigan.record.data.model.PaymentChannel
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.TaskDraft
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A pending create-order command must survive process restoration byte-for-byte: the
 * operation identities, the actual receipt time and the whole form payload are what
 * make a retry the *same* command instead of a duplicate order.
 */
class PendingOrderCommandCodecTest {

    private val form = RestoredOrderForm(
        isShort = false,
        title = "日常托管 | 含分隔符=和换行\n",
        priceYuan = "300.00",
        paymentMode = PaymentMode.INSTALLMENTS,
        deadlineDate = LocalDate.of(2026, 10, 30),
        notes = "备注\u001F含单元分隔符",
        startDate = LocalDate.of(2026, 10, 17),
        endDate = LocalDate.of(2026, 11, 15),
        durationText = "30",
        zoneText = "Asia/Shanghai",
        dailyRefresh = LocalTime.of(4, 0),
        weeklyRefreshDay = DayOfWeek.MONDAY,
        weeklyRefreshTime = LocalTime.of(5, 0),
        tasks = listOf(
            TaskDraft(
                title = "每日签到",
                progressType = ProgressType.CHECKBOX,
                recurrence = RecurrenceType.DAILY,
            ),
            TaskDraft(
                title = "周常 | 副本",
                progressType = ProgressType.NUMERIC,
                startValue = "0",
                targetValue = "100",
                unit = "次",
                recurrence = RecurrenceType.SELECTED_WEEKDAYS,
                weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
            ),
        ),
        initialPaymentEnabled = true,
        initialAmountYuan = "100.00",
        initialChannel = PaymentChannel.OTHER,
        initialNote = "现金",
    )

    @Test
    fun monthlyTaskSurvivesPendingCommand() {
        val monthly = form.copy(tasks = listOf(TaskDraft(title = "月常", recurrence = RecurrenceType.MONTHLY, monthDay = "31")))
        val encoded = PendingOrderCommandCodec.encode(monthly, "monthly-op", "pay-op", 123L)
        assertEquals(monthly, PendingOrderCommandCodec.decode(encoded)?.form)
    }

    @Test
    fun roundTripPreservesEveryFieldAndIdentity() {
        val encoded = PendingOrderCommandCodec.encode(
            form = form,
            creationOperationId = "create-op",
            initialPaymentOperationId = "payment-op",
            initialReceivedAtMillis = 1_757_900_400_123L,
        )
        val restored = PendingOrderCommandCodec.decode(encoded)
        assertNotNull(restored)
        assertEquals("create-op", restored!!.creationOperationId)
        assertEquals("payment-op", restored.initialPaymentOperationId)
        assertEquals(1_757_900_400_123L, restored.initialReceivedAtMillis)
        assertEquals(form, restored.form)
    }

    @Test
    fun malformedValuesFallBackInsteadOfCrashing() {
        assertNull(PendingOrderCommandCodec.decode(""))
        assertNull(PendingOrderCommandCodec.decode("version=v1\ntitle=not-base64!"))
        assertNull(PendingOrderCommandCodec.decode("version=v2\ntitle="))
    }
}
