package com.daigan.record.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.PaymentChannel
import com.daigan.record.data.model.PaymentRequest
import com.daigan.record.data.model.ReceiptCorrectionRequest
import com.daigan.record.data.model.ReceiptStatus
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.hostingDraft
import com.daigan.record.testing.shortDraft
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrderMaintenanceEdgeTest {
    private lateinit var env: OrderTestEnvironment
    private val day = LocalDate.of(2026, 10, 17)
    @Before fun setup() { env = OrderTestEnvironment(ApplicationProvider.getApplicationContext<Context>()) }
    @After fun close() { env.close() }

    @Test
    fun cancellingHostingStopsTodayRenewalAndCompletionAndAllowsOwnerArchive() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val id = env.orderRepository.createHostingOrder(hostingDraft(owner.id, account.id, day, day.plusDays(5)))
        val before = env.orderRepository.observeOrderDetail(id).first()!!
        val task = before.tasks.single()
        assertEquals(id, env.orderRepository.observeTodayHosting().first().single().order.id)
        assertTrue(runCatching { env.ownerRepository.archiveOwner(owner.id) }.isFailure)

        env.orderRepository.cancelOrder(id)

        assertTrue(env.orderRepository.observeTodayHosting().first().isEmpty())
        assertTrue(runCatching { env.orderRepository.renewHosting(id, "cancelled-renewal", 10, 10000) }.isFailure)
        assertTrue(runCatching { env.orderRepository.setOccurrenceCompleted(task.id, true) }.isFailure)
        val after = env.orderRepository.observeOrderDetail(id).first()!!
        assertEquals(before.periods, after.periods)
        assertTrue(after.occurrences.isEmpty())
        env.ownerRepository.archiveOwner(owner.id)
        assertNotNull(env.database.ownerDao().findById(owner.id)!!.archivedAt)
    }

    @Test
    fun datesCannotOverlapAnotherPeriodAndRejectedEditKeepsBothPeriods() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val id = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, day.minusDays(10), day.minusDays(2)),
        )
        val old = env.orderRepository.observeOrderDetail(id).first()!!.currentPeriod!!
        env.orderRepository.renewHosting(id, "edge-renewal", 10, 20000)
        val before = env.orderRepository.observeOrderDetail(id).first()!!
        val next = before.currentPeriod!!
        assertTrue(runCatching {
            env.orderRepository.updateOrderDates(id, old.id, old.startDate, next.startDate)
        }.isFailure)
        assertEquals(before.periods, env.orderRepository.observeOrderDetail(id).first()!!.periods)
    }

    @Test
    fun shortOrderDeadlineCanBeCorrectedAndClearedWithoutChangingPriceOrTasks() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val id = env.orderRepository.createShortOrder(shortDraft(owner.id, account.id, deadline = day))
        val before = env.orderRepository.observeOrderDetail(id).first()!!
        env.orderRepository.updateOrderDates(id, null, null, day.plusDays(3))
        assertEquals(day.plusDays(3), env.database.orderDao().findById(id)!!.deadlineDate)
        env.orderRepository.updateOrderDates(id, null, null, null)
        val after = env.orderRepository.observeOrderDetail(id).first()!!
        assertNull(after.order.deadlineDate)
        assertEquals(before.order.priceCents, after.order.priceCents)
        assertEquals(before.tasks, after.tasks)
        assertEquals(before.receipts, after.receipts)
    }

    @Test
    fun cancelledOrderReceiptCanBeAmendedWithoutReopeningDebt() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val id = env.orderRepository.createShortOrder(shortDraft(owner.id, account.id).copy(paymentMode = com.daigan.record.data.model.PaymentMode.INSTALLMENTS))
        val payment = env.receiptRepository.recordPayment(PaymentRequest(
            orderId = id, periodId = null, amountCents = 4000,
            receivedAt = env.clock.now(), channel = PaymentChannel.WECHAT,
            note = null, operationId = "edge-payment",
        ))
        env.orderRepository.cancelOrder(id)
        val result = env.receiptRepository.correctReceipt(ReceiptCorrectionRequest.Amend(
            operationId = "edge-amend-after-cancel", receiptId = payment.receipt.id,
            amountCents = 3000, receivedAt = env.clock.now(), channel = PaymentChannel.ALIPAY,
            note = "更正实际到账", reason = "金额录错",
        ))
        assertEquals(3000L, result.receipt!!.amountCents)
        val detail = env.orderRepository.observeOrderDetail(id).first()!!
        assertTrue(detail.order.cancelled)
        assertEquals(0L, detail.balance().totalOutstandingCents)
        assertTrue(detail.balance().receivableItems.isEmpty())
        assertEquals(2, detail.receipts.size)
        assertEquals(ReceiptStatus.REPLACED, detail.receipts.single { it.id == payment.receipt.id }.status)
        assertEquals(ReceiptStatus.ACTIVE, detail.receipts.single { it.id == result.receipt!!.id }.status)
        assertEquals(1, detail.receiptCorrections.size)
    }

}
