package com.kuankuan.daigan.review

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kuankuan.daigan.core.time.StatisticsRange
import com.kuankuan.daigan.data.model.InitialPaymentDraft
import com.kuankuan.daigan.data.model.PaymentChannel
import com.kuankuan.daigan.data.model.PaymentMode
import com.kuankuan.daigan.data.model.PaymentRequest
import com.kuankuan.daigan.data.model.ReceiptCorrectionRequest
import com.kuankuan.daigan.data.repository.LedgerSnapshot
import com.kuankuan.daigan.testing.OrderTestEnvironment
import com.kuankuan.daigan.testing.hostingDraft
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
 * Continuous-subscription consistency for the financial read model.
 *
 * Each subscriber stays subscribed while order creation (with an initial receipt),
 * renewal, a payment, an amendment and a void commit. Every emission must be a
 * complete database version: no receipt without its order/period, no temporarily lost
 * history entry and no integrity failure that ends the subscription. The test does not
 * use a debounce/delay in production code; it only waits for the subscriber to observe
 * the committed state.
 */
@RunWith(AndroidJUnit4::class)
class LedgerSnapshotConsistencyTest {

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
    fun subscribersNeverObserveMixedFinancialSnapshots() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.clock.set(Instant.parse("2026-10-05T02:00:00Z"))

        val snapshotProblems = Collections.synchronizedList(mutableListOf<String>())
        val statisticsProblems = Collections.synchronizedList(mutableListOf<String>())
        val historyProblems = Collections.synchronizedList(mutableListOf<String>())
        val seenReceiptIds = Collections.synchronizedSet(mutableSetOf<String>())
        val lastStatisticsPaid = AtomicLong(-1L)
        val statisticsEmissions = AtomicInteger(0)

        val snapshotJob = launch(Dispatchers.Default) {
            env.ledgerSnapshotSource.observe().collect { snapshot ->
                snapshotProblems += integrityProblems(snapshot)
                val ids = snapshot.receipts.map { it.id }.toSet()
                synchronized(seenReceiptIds) {
                    val missing = seenReceiptIds - ids
                    if (missing.isNotEmpty()) {
                        snapshotProblems += "receipt history temporarily disappeared: $missing"
                    }
                    seenReceiptIds.addAll(ids)
                }
            }
        }
        val historyJob = launch(Dispatchers.Default) {
            var previous = emptySet<String>()
            env.receiptRepository.observeReceiptHistory().collect { entries ->
                val ids = entries.map { it.receipt.id }.toSet()
                val missing = previous - ids
                if (missing.isNotEmpty()) {
                    historyProblems += "history entries disappeared: $missing"
                }
                previous = ids
            }
        }
        val statisticsJob = launch(Dispatchers.Default) {
            env.statisticsRepository.observeStatistics(
                StatisticsRange.MONTH,
                ZoneId.of("Asia/Shanghai"),
                Instant.parse("2026-10-05T02:00:00Z"),
            ).collect { statistics ->
                statisticsEmissions.incrementAndGet()
                if (statistics.outstandingCents < 0 || statistics.receivedCents < 0) {
                    statisticsProblems += "negative money in statistics: $statistics"
                }
                lastStatisticsPaid.set(statistics.receivedCents)
            }
        }

        try {
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
                        operationId = "snapshot-initial",
                    ),
                ),
            )
            awaitPaid(10_000L, lastStatisticsPaid)

            val oldPeriod = env.database.orderDao().findPeriods(orderId).single()

            env.clock.set(Instant.parse("2026-10-12T02:00:00Z"))
            val renewal = env.orderRepository.renewHosting(orderId, "snapshot-renew", 10, 20_000)
            awaitPaid(10_000L, lastStatisticsPaid)

            val payment = env.receiptRepository.recordPayment(
                PaymentRequest(
                    orderId = orderId,
                    periodId = renewal.periodId,
                    amountCents = 5_000,
                    receivedAt = env.clock.now(),
                    channel = PaymentChannel.WECHAT,
                    note = null,
                    operationId = "snapshot-payment",
                ),
            )
            awaitPaid(15_000L, lastStatisticsPaid)

            env.receiptRepository.correctReceipt(
                ReceiptCorrectionRequest.Amend(
                    operationId = "snapshot-amend",
                    receiptId = payment.receipt.id,
                    amountCents = 6_000,
                    receivedAt = payment.receipt.receivedAt,
                    channel = PaymentChannel.ALIPAY,
                    note = null,
                    reason = "补记一元",
                ),
            )
            awaitPaid(16_000L, lastStatisticsPaid)

            val initialReceipt = env.database.receiptDao().findAll()
                .single { it.periodId == oldPeriod.id }
            env.receiptRepository.correctReceipt(
                ReceiptCorrectionRequest.Void(
                    operationId = "snapshot-void",
                    receiptId = initialReceipt.id,
                    reason = "退回首款",
                ),
            )
            awaitPaid(6_000L, lastStatisticsPaid)

            assertTrue("The snapshot subscription must stay alive", snapshotJob.isActive)
            assertTrue("The history subscription must stay alive", historyJob.isActive)
            assertTrue("The statistics subscription must stay alive", statisticsJob.isActive)
            assertTrue("Statistics must have emitted", statisticsEmissions.get() > 0)
            assertTrue("Mixed ledger versions: $snapshotProblems", snapshotProblems.isEmpty())
            assertTrue("History entries disappeared: $historyProblems", historyProblems.isEmpty())
            assertTrue("Statistics integrity failure: $statisticsProblems", statisticsProblems.isEmpty())

            // Final state still agrees across all three read paths.
            val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
            val statistics = env.statisticsRepository.loadStatistics(
                StatisticsRange.MONTH,
                ZoneId.of("Asia/Shanghai"),
                Instant.parse("2026-10-12T02:00:00Z"),
            )
            assertEquals(detail.balance().totalPaidCents, statistics.receivedCents)
            assertEquals(6_000L, statistics.receivedCents)
            // The void restored the old period's 300 debt; only the new period is paid.
            assertEquals(44_000L, statistics.outstandingCents)
        } finally {
            snapshotJob.cancel()
            historyJob.cancel()
            statisticsJob.cancel()
        }
    }

    private suspend fun awaitPaid(expected: Long, current: AtomicLong) {
        withTimeout(10_000) {
            while (current.get() != expected) {
                delay(10)
            }
        }
    }

    /** Referential invariants every emission must satisfy as one database version. */
    private fun integrityProblems(snapshot: LedgerSnapshot): List<String> {
        val problems = mutableListOf<String>()
        val orders = snapshot.orders.associateBy { it.id }
        val periods = snapshot.periods.associateBy { it.id }
        val accounts = snapshot.accounts.associateBy { it.id }
        val owners = snapshot.owners.associateBy { it.id }

        snapshot.orders.forEach { order ->
            val account = accounts[order.accountId]
            if (account == null) {
                problems += "order ${order.id} has no account"
            } else if (owners[account.ownerId] == null) {
                problems += "account ${account.id} has no owner"
            }
        }
        snapshot.periods.forEach { period ->
            if (orders[period.orderId] == null) {
                problems += "period ${period.id} has no order"
            }
        }
        snapshot.receipts.forEach { receipt ->
            if (orders[receipt.orderId] == null) {
                problems += "receipt ${receipt.id} has no order"
            }
            receipt.periodId?.let { periodId ->
                val period = periods[periodId]
                if (period == null) {
                    problems += "receipt ${receipt.id} has no period"
                } else if (period.orderId != receipt.orderId) {
                    problems += "receipt ${receipt.id} points at another order's period"
                }
            }
        }
        snapshot.corrections.forEach { correction ->
            if (snapshot.receipts.none { it.id == correction.receiptId }) {
                problems += "correction ${correction.id} has no receipt"
            }
        }
        return problems
    }
}
