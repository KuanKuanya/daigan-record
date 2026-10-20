package com.kuankuan.daigan.review

import com.kuankuan.daigan.data.model.*
import com.kuankuan.daigan.feature.orders.PendingOrderCommandCodec
import com.kuankuan.daigan.feature.orders.RestoredOrderForm
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Phase4PendingCompatibilityReviewTest {
    @Test fun phaseThreePendingCommandStillDecodesWithoutNewTemplateFields() {
        val form = RestoredOrderForm(
            isShort = true, title = "待重试原订单", priceYuan = "100.00",
            paymentMode = PaymentMode.INSTALLMENTS, deadlineDate = null, notes = "原备注",
            startDate = LocalDate.of(2026, 10, 17), endDate = LocalDate.of(2026, 10, 18),
            durationText = "2", zoneText = "Asia/Shanghai", dailyRefresh = LocalTime.of(4, 0),
            weeklyRefreshDay = DayOfWeek.MONDAY, weeklyRefreshTime = LocalTime.of(5, 0),
            tasks = listOf(TaskDraft(title = "原任务")), initialPaymentEnabled = true,
            initialAmountYuan = "50.00", initialChannel = PaymentChannel.WECHAT, initialNote = "",
        )
        val encoded = PendingOrderCommandCodec.encode(form, "original-create", "original-receipt", 123456L)
        // The Phase 3 v1 encoder had exactly this format, without the three new keys.
        val phaseThreePayload = encoded.lineSequence()
            .filterNot { it.startsWith("sourceTemplate") || it.startsWith("explicitShortCalendar=") }.joinToString("\n")
        val restored = PendingOrderCommandCodec.decode(phaseThreePayload)
        assertNotNull("An old pending command must not be discarded because optional template fields are absent",
            restored)
        assertEquals("original-create", restored!!.creationOperationId)
        assertEquals("original-receipt", restored.initialPaymentOperationId)
        assertEquals(form, restored.form)
        assertNull(restored.form.sourceTemplateId)
        assertEquals(123456L, restored.initialReceivedAtMillis)
        val explicit = form.copy(explicitShortCalendar = true)
        assertEquals(explicit, PendingOrderCommandCodec.decode(
            PendingOrderCommandCodec.encode(explicit, "original-create", "original-receipt", 123456L),
        )!!.form)
        // Only added fields are optional: a missing original field must still fail.
        assertNull(PendingOrderCommandCodec.decode(phaseThreePayload.lineSequence()
            .filterNot { it.startsWith("priceYuan=") }.joinToString("\n")))
    }
}
