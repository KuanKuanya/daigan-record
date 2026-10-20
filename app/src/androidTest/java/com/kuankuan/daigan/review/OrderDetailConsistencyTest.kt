package com.kuankuan.daigan.review

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kuankuan.daigan.data.model.InitialPaymentDraft
import com.kuankuan.daigan.data.model.Money
import com.kuankuan.daigan.data.model.OrderDetail
import com.kuankuan.daigan.data.model.PaymentChannel
import com.kuankuan.daigan.data.model.PaymentMode
import com.kuankuan.daigan.data.model.PaymentRequest
import com.kuankuan.daigan.data.model.ReceiptCorrectionRequest
import com.kuankuan.daigan.data.model.ReceiptStatus
import com.kuankuan.daigan.testing.OrderTestEnvironment
import com.kuankuan.daigan.testing.hostingDraft
import java.time.Instant
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Continuous-subscription consistency for one order detail.
 *
 * The detail read is its own per-order snapshot (it is not the global
 * [com.kuankuan.daigan.data.repository.LedgerSnapshotSource]); this test proves that
 * every emission is nevertheless one database version: a receipt always has its
 * period, the derived balance always equals the active receipts it carries, and no
 * receipt history entry ever disappears.
 */
@RunWith(AndroidJUnit4::class)
class OrderDetailConsistencyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var env: OrderTestEnvironment

    @Before
    fun setUp() {
        env = OrderTestEnvironment(context)
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun detailSubscriberNeverSeesAMixedFinancialVersion() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.clock.set(Instant.parse("2026-10-05T02:00:00Z"))
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(
                owner.id,
                account.id,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 10),
                priceYuan = "300.00",
            ).copy(
                paymentMode = PaymentMode.INSTALLMENTS,
                initialPayment = InitialPaymentDraft(
                    amountYuan = "100.00",
                    operationId = "detail-initial",
                ),
            ),
        )

        val problems = Collections.synchronizedList(mutableListOf<String>())
        val seenReceiptIds = Collections.synchronizedSet(mutableSetOf<String>())
        val paid = AtomicLong(-1)
        val emissions = AtomicInteger(0)

        val job = launch(Dispatchers.Default) {
            env.orderRepository.observeOrderDetail(orderId).collect { detail ->
                if (detail == null) {
                    problems += "the detail disappeared while subscribed"
                    return@collect
                }
                emissions.incrementAndGet()
                problems += problemsOf(detail)
                synchronized(seenReceiptIds) {
                    val ids = detail.receipts.map { it.id }.toSet()
                    val missing = seenReceiptIds - ids
                    if (missing.isNotEmpty()) {
                        problems += "receipt history temporarily disappeared: $missing"
                    }
                    seenReceiptIds.addAll(ids)
                }
                paid.set(detail.balance().totalPaidCents)
            }
        }

        try {
            awaitPaid(10_000L, paid)
            val period = env.database.orderDao().findPeriods(orderId).single()
            val payment = env.receiptRepository.recordPayment(
                PaymentRequest(
                    orderId = orderId,
                    periodId = period.id,
                    amountCents = 5_000,
                    receivedAt = env.clock.now(),
                    channel = PaymentChannel.WECHAT,
                    note = null,
                    operationId = "detail-payment",
                ),
            )
            awaitPaid(15_000L, paid)

            env.receiptRepository.correctReceipt(
                ReceiptCorrectionRequest.Amend(
                    operationId = "detail-amend",
                    receiptId = payment.receipt.id,
                    amountCents = 6_000,
                    receivedAt = payment.receipt.receivedAt,
                    channel = PaymentChannel.ALIPAY,
                    note = null,
                    reason = "补记一元",
                ),
            )
            awaitPaid(16_000L, paid)

            val initialReceipt = env.database.receiptDao().findForOrder(orderId)
                .single { it.operationId == "detail-initial" }
            env.receiptRepository.correctReceipt(
                ReceiptCorrectionRequest.Void(
                    operationId = "detail-void",
                    receiptId = initialReceipt.id,
                    reason = "退回首款",
                ),
            )
            awaitPaid(6_000L, paid)

            assertTrue("the detail subscription must stay alive", job.isActive)
            assertTrue("the detail must have emitted", emissions.get() > 0)
            assertTrue("mixed detail versions: $problems", problems.isEmpty())

            // The final detail agrees with one consistent global ledger read.
            val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
            val snapshot = env.ledgerSnapshotSource.load()
            val activePaid = Money.sumCents(
                snapshot.receipts
                    .filter { it.orderId == orderId && it.status == ReceiptStatus.ACTIVE }
                    .map { it.amountCents },
            )
            assertEquals(activePaid, detail.balance().totalPaidCents)
            assertEquals(6_000L, detail.balance().totalPaidCents)
            assertEquals(24_000L, detail.balance().totalOutstandingCents)
        } finally {
            job.cancel()
        }
    }

    private fun problemsOf(detail: OrderDetail): List<String> {
        val problems = mutableListOf<String>()
        val periods = detail.periods.associateBy { it.id }
        detail.receipts.forEach { receipt ->
            if (receipt.orderId != detail.order.id) {
                problems += "receipt ${receipt.id} belongs to another order"
            }
            val periodId = receipt.periodId
            if (periodId != null) {
                val period = periods[periodId]
                when {
                    period == null ->
                        problems += "receipt ${receipt.id} has no period $periodId in this emission"
                    period.orderId != receipt.orderId ->
                        problems += "receipt ${receipt.id} points at another order's period"
                }
            }
        }
        detail.receiptCorrections.forEach { correction ->
            if (detail.receipts.none { it.id == correction.receiptId }) {
                problems += "correction ${correction.id} has no receipt in this emission"
            }
        }
        val activePaid = Money.sumCents(
            detail.receipts.filter { it.status == ReceiptStatus.ACTIVE }.map { it.amountCents },
        )
        val balance = detail.balance()
        if (balance.totalPaidCents != activePaid) {
            problems += "balance paid ${balance.totalPaidCents} != active receipts $activePaid"
        }
        if (balance.totalPaidCents < 0 || balance.totalOutstandingCents < 0) {
            problems += "negative money in the detail balance"
        }
        return problems
    }

    private suspend fun awaitPaid(expected: Long, current: AtomicLong) {
        withTimeout(10_000) {
            while (current.get() != expected) delay(10)
        }
    }
}
