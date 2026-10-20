package com.kuankuan.daigan.review

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kuankuan.daigan.core.time.StatisticsRange
import com.kuankuan.daigan.data.model.InitialPaymentDraft
import com.kuankuan.daigan.data.model.Money
import com.kuankuan.daigan.data.model.OrderType
import com.kuankuan.daigan.data.model.PaymentChannel
import com.kuankuan.daigan.data.model.PaymentMode
import com.kuankuan.daigan.data.model.PaymentRequest
import com.kuankuan.daigan.data.model.ReceiptCorrectionRequest
import com.kuankuan.daigan.data.model.ReceiptStatus
import com.kuankuan.daigan.testing.OrderTestEnvironment
import com.kuankuan.daigan.testing.hostingDraft
import com.kuankuan.daigan.testing.shortDraft
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 3 financial contracts against real Room.
 *
 * Every test uses the production repositories and real SQLite transactions; no fake
 * balance or DAO stub participates. Amounts are integer cents throughout.
 */
@RunWith(AndroidJUnit4::class)
class Phase3FinancialContractTest {

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

    // ---------------------------------------------------- settlement and cents

    @Test
    fun installmentsAllowPartialPaymentsAndSettleExactly() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createShortOrder(
            shortDraft(owner.id, account.id, priceYuan = "100.00")
                .copy(paymentMode = PaymentMode.INSTALLMENTS),
        )

        env.receiptRepository.recordPayment(payment(orderId, 4_000, "p-40"))
        var balance = env.orderRepository.observeOrderDetail(orderId).first()!!.balance()
        assertEquals(4_000L, balance.totalPaidCents)
        assertEquals(6_000L, balance.totalOutstandingCents)
        assertEquals(1, balance.receivableItems.size)

        env.receiptRepository.recordPayment(payment(orderId, 6_000, "p-60"))
        balance = env.orderRepository.observeOrderDetail(orderId).first()!!.balance()
        assertEquals(10_000L, balance.totalPaidCents)
        assertEquals(0L, balance.totalOutstandingCents)
        assertTrue("A settled item leaves the outstanding list", balance.receivableItems.isEmpty())

        // History is never removed when the item is settled.
        assertEquals(2, env.database.receiptDao().findAll().size)
    }

    @Test
    fun oneTimeOrdersOnlyAcceptTheFullRemainingBalance() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createShortOrder(
            shortDraft(owner.id, account.id, priceYuan = "50.00"),
        )

        val partial = runCatching { env.receiptRepository.recordPayment(payment(orderId, 2_000, "p-part")) }
        assertTrue("ONE_TIME must reject a partial payment", partial.isFailure)
        assertEquals(0, env.database.receiptDao().findAll().size)

        env.receiptRepository.recordPayment(payment(orderId, 5_000, "p-full"))
        assertEquals(
            0L,
            env.orderRepository.observeOrderDetail(orderId).first()!!.balance().totalOutstandingCents,
        )
    }

    @Test
    fun invalidPrecisionZeroAndOverflowAreRejectedBeforeWriting() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createShortOrder(
            shortDraft(owner.id, account.id, priceYuan = "100.00")
                .copy(paymentMode = PaymentMode.INSTALLMENTS),
        )

        assertNull(Money.parseYuanToCentsOrNull("100.001"))
        assertNull(Money.parsePositiveYuanToCentsOrNull("0"))
        assertNull(Money.parsePositiveYuanToCentsOrNull("-1"))
        assertNull(
            "Above the per-amount ceiling",
            Money.parseYuanToCentsOrNull("100000000.00"),
        )
        assertEquals(Money.MAX_CENTS, Money.parseYuanToCentsOrNull("99999999.99"))
        assertEquals(
            "One cent more than the balance is rejected, not rounded",
            10_000L,
            env.orderRepository.observeOrderDetail(orderId).first()!!.balance().totalOutstandingCents,
        )

        assertTrue(
            runCatching {
                env.receiptRepository.recordPayment(payment(orderId, 10_001, "over"))
            }.isFailure,
        )
        assertTrue(
            runCatching {
                env.receiptRepository.recordPayment(payment(orderId, Money.MAX_CENTS + 1, "huge"))
            }.isFailure,
        )
        assertEquals(0, env.database.receiptDao().findAll().size)
    }

    // ----------------------------------------------- idempotency and concurrency

    @Test
    fun identicalCreationRetryReturnsOriginalOrderAndConflictingPayloadIsRejected() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val draft = shortDraft(owner.id, account.id).copy(
            initialPayment = InitialPaymentDraft("100.00", operationId = "initial-op"),
        )

        val first = env.orderRepository.createShortOrder(draft)
        val retry = env.orderRepository.createShortOrder(draft)
        assertEquals(first, retry)
        assertEquals(1, env.database.orderDao().countForAccount(account.id))
        assertEquals(1, env.database.receiptDao().findAll().size)

        val conflicting = draft.copy(title = "被改过的名称")
        val rejected = runCatching { env.orderRepository.createShortOrder(conflicting) }
        assertTrue("Same operation id, different payload must be rejected", rejected.isFailure)
        assertEquals(1, env.database.orderDao().countForAccount(account.id))
    }

    @Test
    fun unpaidCreationIsRetrySafeWithoutAFabricatedReceipt() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val draft = shortDraft(owner.id, account.id)

        val first = env.orderRepository.createShortOrder(draft)
        val same = env.orderRepository.createShortOrder(draft)
        assertEquals("An identical unpaid retry returns the original order", first, same)
        assertEquals(1, env.database.orderDao().countForAccount(account.id))
        assertEquals(0, env.database.receiptDao().findAll().size)
        assertEquals(1, env.database.creationOperationDao().count())

        // An explicitly new command (new id) creates a second order.
        val second = env.orderRepository.createShortOrder(
            draft.copy(notes = "第二张单", creationOperationId = "second-unpaid-command"),
        )
        assertNotEquals(first, second)
        assertEquals(2, env.database.orderDao().countForAccount(account.id))
        assertEquals(2, env.database.creationOperationDao().count())
    }

    @Test
    fun concurrentIdenticalCreationCreatesExactlyOneOrderAndReceipt() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val draft = hostingDraft(
            owner.id,
            account.id,
            LocalDate.of(2026, 10, 17),
            LocalDate.of(2026, 11, 15),
        ).copy(initialPayment = InitialPaymentDraft("300.00", operationId = "concurrent-initial"))

        val ids = coroutineScope {
            (1..2).map { async { env.orderRepository.createHostingOrder(draft) } }.awaitAll()
        }
        assertEquals(1, ids.distinct().size)
        assertEquals(1, env.database.orderDao().countForAccount(account.id))
        assertEquals(1, env.database.receiptDao().findAll().size)
        assertEquals(1, env.database.orderDao().countPeriods(ids.first()))
    }

    @Test
    fun concurrentPaymentsCannotOverpayAndIdenticalRetryAddsNothing() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createShortOrder(
            shortDraft(owner.id, account.id, priceYuan = "100.00")
                .copy(paymentMode = PaymentMode.INSTALLMENTS),
        )
        val same = payment(orderId, 4_000, "same-op")
        coroutineScope {
            (1..2).map { async { runCatching { env.receiptRepository.recordPayment(same) } } }.awaitAll()
        }
        assertEquals(1, env.database.receiptDao().findAll().size)

        val results = coroutineScope {
            listOf("a", "b").map { op ->
                async { runCatching { env.receiptRepository.recordPayment(payment(orderId, 6_000, op)) } }
            }.awaitAll()
        }
        assertEquals("Exactly one concurrent payment may win", 1, results.count { it.isSuccess })
        val paid = env.orderRepository.observeOrderDetail(orderId).first()!!.balance().totalPaidCents
        assertEquals("Paid amount must equal price, never exceed it", 10_000L, paid)
    }

    // ------------------------------------------------------ relationships, atomic

    @Test
    fun invalidOrderPeriodRelationshipsAreRejected() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val day = LocalDate.of(2026, 10, 17)
        val hostingA = env.orderRepository.createHostingOrder(hostingDraft(owner.id, account.id, day, day.plusDays(5)))
        val hostingB = env.orderRepository.createHostingOrder(hostingDraft(owner.id, account.id, day, day.plusDays(5)))
        val periodB = env.database.orderDao().findPeriods(hostingB).single()
        val shortOrder = env.orderRepository.createShortOrder(shortDraft(owner.id, account.id))
        val periodA = env.database.orderDao().findPeriods(hostingA).single()

        assertTrue(
            "Hosting payment must name its period",
            runCatching { env.receiptRepository.recordPayment(payment(hostingA, 1_000, "no-period")) }.isFailure,
        )
        assertTrue(
            "A period of another order cannot be used",
            runCatching {
                env.receiptRepository.recordPayment(payment(hostingA, 1_000, "wrong-period", periodB.id))
            }.isFailure,
        )
        assertTrue(
            "A short order cannot carry a hosting period",
            runCatching {
                env.receiptRepository.recordPayment(payment(shortOrder, 1_000, "short-period", periodA.id))
            }.isFailure,
        )
        assertEquals(0, env.database.receiptDao().findAll().size)
    }

    @Test
    fun creationWithReceiptRollsBackTheWholeCommand() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val future = env.clock.now().plusSeconds(60)
        val draft = shortDraft(owner.id, account.id, priceYuan = "100.00").copy(
            initialPayment = InitialPaymentDraft(
                amountYuan = "100.00",
                operationId = "future-receipt",
                receivedAt = future,
            ),
        )

        val failure = runCatching { env.orderRepository.createShortOrder(draft) }
        assertTrue("A receipt in the future must abort the whole creation", failure.isFailure)
        assertEquals(0, env.database.orderDao().countForAccount(account.id))
        assertEquals(0, env.database.receiptDao().findAll().size)
        assertEquals(0, env.database.creationOperationDao().count())
        assertEquals(0, env.database.orderDao().findAllTasks().size)
    }

    // ------------------------------------------------------------- corrections

    @Test
    fun amendmentAndVoidPreserveAuditAndRecoverBalances() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createShortOrder(
            shortDraft(owner.id, account.id, priceYuan = "100.00")
                .copy(paymentMode = PaymentMode.INSTALLMENTS),
        )
        val paid = env.receiptRepository.recordPayment(payment(orderId, 4_000, "original"))

        val amend = ReceiptCorrectionRequest.Amend(
            operationId = "amend-1",
            receiptId = paid.receipt.id,
            amountCents = 6_000,
            receivedAt = paid.receipt.receivedAt,
            channel = PaymentChannel.ALIPAY,
            note = null,
            reason = "金额记少",
        )
        val amended = env.receiptRepository.correctReceipt(amend)
        assertEquals(6_000L, amended.receipt!!.amountCents)
        assertEquals(ReceiptStatus.REPLACED, env.database.receiptDao().findById(paid.receipt.id)!!.status)
        var detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(6_000L, detail.balance().totalPaidCents)
        val audit = detail.receiptCorrections.single()
        assertEquals(4_000L, audit.previousAmountCents)
        assertEquals(6_000L, audit.newAmountCents)
        assertEquals("金额记少", audit.reason)

        // Identical retry returns the original audit without adding history.
        val retried = env.receiptRepository.correctReceipt(amend)
        assertTrue(retried.alreadyApplied)
        assertEquals(1, env.database.receiptDao().findAllCorrections().size)
        // A conflicting audit reason is a different command.
        assertTrue(
            runCatching {
                env.receiptRepository.correctReceipt(amend.copy(reason = "另一个原因"))
            }.isFailure,
        )
        assertEquals(1, env.database.receiptDao().findAllCorrections().size)

        val void = ReceiptCorrectionRequest.Void("void-1", amended.receipt.id, "客户退款")
        env.receiptRepository.correctReceipt(void)
        detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(0L, detail.balance().totalPaidCents)
        assertEquals(ReceiptStatus.VOIDED, env.database.receiptDao().findById(amended.receipt.id)!!.status)
        assertTrue(env.receiptRepository.correctReceipt(void).alreadyApplied)
        assertTrue(
            runCatching {
                env.receiptRepository.correctReceipt(void.copy(reason = "换个说法"))
            }.isFailure,
        )
        assertEquals(2, env.database.receiptDao().findAllCorrections().size)
    }

    @Test
    fun amendmentCannotExceedTheRecoverableBalance() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createShortOrder(
            shortDraft(owner.id, account.id, priceYuan = "100.00")
                .copy(paymentMode = PaymentMode.INSTALLMENTS),
        )
        val first = env.receiptRepository.recordPayment(payment(orderId, 4_000, "a"))
        env.receiptRepository.recordPayment(payment(orderId, 4_000, "b"))

        val tooLarge = ReceiptCorrectionRequest.Amend(
            operationId = "amend-over",
            receiptId = first.receipt.id,
            amountCents = 7_000,
            receivedAt = first.receipt.receivedAt,
            channel = PaymentChannel.WECHAT,
            note = null,
            reason = null,
        )
        assertTrue(runCatching { env.receiptRepository.correctReceipt(tooLarge) }.isFailure)
        assertEquals(
            8_000L,
            env.orderRepository.observeOrderDetail(orderId).first()!!.balance().totalPaidCents,
        )
    }

    // ---------------------------------------------------- timestamps and months

    @Test
    fun initialReceiptKeepsItsActualMonthWhileTheOrderCountsInOctober() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.clock.set(Instant.parse("2026-10-18T02:00:00Z"))
        val september = Instant.parse("2026-09-15T03:30:00Z")
        val draft = hostingDraft(
            owner.id,
            account.id,
            LocalDate.of(2026, 10, 18),
            LocalDate.of(2026, 11, 16),
            priceYuan = "100.00",
        ).copy(
            initialPayment = InitialPaymentDraft(
                amountYuan = "100.00",
                operationId = "september-initial",
                receivedAt = september,
            ),
        )
        val orderId = env.orderRepository.createHostingOrder(draft)

        val zone = ZoneId.of("Asia/Shanghai")
        val october = env.statisticsRepository.loadStatistics(
            StatisticsRange.MONTH,
            zone,
            Instant.parse("2026-10-18T02:00:00Z"),
        )
        assertEquals("October income must exclude the September receipt", 0L, october.receivedCents)
        assertEquals("The October order still counts", 1, october.orderCount)
        assertEquals(1, october.hostingOrderCount)

        val septemberWindow = env.statisticsRepository.loadStatistics(
            StatisticsRange.MONTH,
            zone,
            Instant.parse("2026-09-20T02:00:00Z"),
        )
        assertEquals(10_000L, septemberWindow.receivedCents)
        assertEquals(0, septemberWindow.orderCount)

        val all = env.statisticsRepository.loadStatistics(
            StatisticsRange.ALL,
            zone,
            Instant.parse("2026-10-18T02:00:00Z"),
        )
        assertEquals(10_000L, all.receivedCents)

        // A retry keeps the original order and the originally submitted receipt time.
        val retried = env.orderRepository.createHostingOrder(draft)
        assertEquals(orderId, retried)
        val receipt = env.database.receiptDao().findAll().single()
        assertEquals(september.toEpochMilli(), receipt.receivedAt.toEpochMilli())
        assertEquals(1, env.database.orderDao().countForAccount(account.id))
    }

    @Test
    fun monthBoundariesUseTheReportingTimezoneAndAllTimeDebtStaysVisible() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.clock.set(Instant.parse("2026-10-31T16:30:00Z")) // 2026-11-01 00:30 Shanghai
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(
                owner.id,
                account.id,
                LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 30),
                priceYuan = "300.00",
            ).copy(
                paymentMode = PaymentMode.INSTALLMENTS,
                initialPayment = InitialPaymentDraft(
                    amountYuan = "100.00",
                    operationId = "boundary-initial",
                    receivedAt = Instant.parse("2026-10-31T15:59:00Z"), // 23:59 Shanghai
                ),
            ),
        )

        val shanghai = ZoneId.of("Asia/Shanghai")
        val october = env.statisticsRepository.loadStatistics(
            StatisticsRange.MONTH,
            shanghai,
            Instant.parse("2026-10-31T10:00:00Z"),
        )
        assertEquals(10_000L, october.receivedCents)
        assertEquals("The order was created in November in Shanghai", 0, october.orderCount)
        assertEquals("All-time debt ignores the month filter", 20_000L, october.outstandingCents)
        assertEquals(1, october.outstandingItems.size)

        val november = env.statisticsRepository.loadStatistics(
            StatisticsRange.MONTH,
            shanghai,
            Instant.parse("2026-11-01T02:00:00Z"),
        )
        assertEquals(0L, november.receivedCents)
        assertEquals(1, november.orderCount)
        assertEquals(20_000L, november.outstandingCents)

        val utc = env.statisticsRepository.loadStatistics(
            StatisticsRange.MONTH,
            ZoneId.of("UTC"),
            Instant.parse("2026-10-31T10:00:00Z"),
        )
        assertEquals("UTC still sees the October order", 1, utc.orderCount)
        assertEquals(10_000L, utc.receivedCents)

        // Explicitly paying the selected current period does not touch history.
        val periodId = env.database.orderDao().findPeriods(orderId).single().id
        env.receiptRepository.recordPayment(payment(orderId, 20_000, "settle", periodId))
        assertEquals(
            0L,
            env.orderRepository.observeOrderDetail(orderId).first()!!.balance().totalOutstandingCents,
        )
    }

    // ------------------------------------------------- archiving and completion

    @Test
    fun archivedExpiredAndCompletedDebtRemainsCollectibleWhileTasksStayReadOnly() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.clock.set(Instant.parse("2026-10-05T02:00:00Z"))
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(
                owner.id,
                account.id,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 10),
                priceYuan = "200.00",
            ).copy(paymentMode = PaymentMode.INSTALLMENTS),
        )
        val period = env.database.orderDao().findPeriods(orderId).single()
        val taskId = env.database.orderDao().findTasks(orderId).single().id
        env.orderRepository.setOccurrenceCompleted(taskId, true)

        env.clock.set(Instant.parse("2026-10-17T02:00:00Z"))
        env.ownerRepository.archiveOwner(owner.id)

        env.receiptRepository.recordPayment(payment(orderId, 8_000, "archived-debt", period.id))
        assertEquals(
            12_000L,
            env.orderRepository.observeOrderDetail(orderId).first()!!.balance().totalOutstandingCents,
        )
        val work = runCatching { env.orderRepository.setOccurrenceCompleted(taskId, false) }
        assertTrue("Archived tasks stay read-only", work.isFailure)
        assertEquals(
            "The archived debt is still in the all-time list",
            12_000L,
            env.statisticsRepository.loadStatistics(
                StatisticsRange.MONTH,
                ZoneId.of("Asia/Shanghai"),
                Instant.parse("2026-10-17T02:00:00Z"),
            ).outstandingCents,
        )

        // A completed short order keeps its debt and accepts payment.
        val (owner2, account2) = env.seedOwnerAndAccount("完工老板", uid = "done-1")
        val shortId = env.orderRepository.createShortOrder(shortDraft(owner2.id, account2.id))
        val shortTask = env.database.orderDao().findTasks(shortId).single().id
        env.orderRepository.setShortTaskCompleted(shortTask, true)
        assertTrue(env.orderRepository.observeOrderDetail(shortId).first()!!.order.completed)
        env.receiptRepository.recordPayment(payment(shortId, 10_000, "completed-debt"))
        assertEquals(
            0L,
            env.orderRepository.observeOrderDetail(shortId).first()!!.balance().totalOutstandingCents,
        )
    }

    // --------------------------------------------------------------- renewal

    @Test
    fun renewalKeepsOldDebtAndReceiptsAndAddsNoOrderCount() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.clock.set(Instant.parse("2026-10-05T02:00:00Z"))
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(
                owner.id,
                account.id,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 10),
                priceYuan = "300.00",
            ).copy(paymentMode = PaymentMode.INSTALLMENTS),
        )
        val oldPeriod = env.database.orderDao().findPeriods(orderId).single()
        env.receiptRepository.recordPayment(payment(orderId, 10_000, "old-period", oldPeriod.id))

        env.clock.set(Instant.parse("2026-10-12T02:00:00Z"))
        val renewal = env.orderRepository.renewHosting(orderId, "renew-1", 10, 20_000)
        assertEquals(1, renewal.periodIndex)

        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(2, detail.periods.size)
        val old = detail.periods.first { it.id == oldPeriod.id }
        val new = detail.periods.first { it.id == renewal.periodId }
        val balance = detail.balance()
        assertEquals(10_000L, balance.items.first { it.periodId == old.id }.paidCents)
        assertEquals(20_000L, balance.items.first { it.periodId == old.id }.outstandingCents)
        assertEquals(0L, balance.items.first { it.periodId == new.id }.paidCents)
        assertEquals(20_000L, balance.items.first { it.periodId == new.id }.outstandingCents)
        assertEquals(1, env.database.orderDao().countForAccount(account.id))
        assertEquals(1, detail.receipts.size)
        assertTrue("The new period starts with zero receipts", detail.receiptsForPeriod(new.id).isEmpty())

        val statistics = env.statisticsRepository.loadStatistics(
            StatisticsRange.MONTH,
            ZoneId.of("Asia/Shanghai"),
            Instant.parse("2026-10-12T02:00:00Z"),
        )
        assertEquals("Renewal never adds an order", 1, statistics.orderCount)

        // The old period can still be settled explicitly after renewal.
        env.receiptRepository.recordPayment(payment(orderId, 20_000, "old-debt-after-renewal", old.id))
        val after = env.orderRepository.observeOrderDetail(orderId).first()!!.balance()
        assertEquals(0L, after.items.first { it.periodId == old.id }.outstandingCents)
        assertEquals(20_000L, after.items.first { it.periodId == new.id }.outstandingCents)
    }

    // --------------------------------------------------- persistence and screens

    @Test
    fun timestampRetrySurvivesDatabaseReopenForPaymentAndAmendment() = runBlocking {
        val name = "phase3-reopen-${System.nanoTime()}.db"
        val first = OrderTestEnvironment(context, databaseName = name, inMemory = false)
        val (owner, account) = first.seedOwnerAndAccount()
        val orderId = first.orderRepository.createShortOrder(
            shortDraft(owner.id, account.id, priceYuan = "100.00")
                .copy(paymentMode = PaymentMode.INSTALLMENTS),
        )
        val subMillisecond = first.clock.now().minusSeconds(2).plusNanos(987_654)
        val request = PaymentRequest(
            orderId,
            null,
            4_000,
            subMillisecond,
            PaymentChannel.WECHAT,
            null,
            "reopen-payment",
        )
        val stored = first.receiptRepository.recordPayment(request)
        assertEquals(
            subMillisecond.toEpochMilli(),
            stored.receipt.receivedAt.toEpochMilli(),
        )
        first.close(deleteDatabaseFile = false)

        val reopened = OrderTestEnvironment(context, databaseName = name, inMemory = false)
        try {
            val retry = reopened.receiptRepository.recordPayment(request)
            assertTrue("Sub-millisecond retry must match after a Room round trip", retry.alreadyApplied)
            assertEquals(1, reopened.database.receiptDao().findAll().size)

            val amend = ReceiptCorrectionRequest.Amend(
                operationId = "reopen-amend",
                receiptId = retry.receipt.id,
                amountCents = 5_000,
                receivedAt = subMillisecond,
                channel = PaymentChannel.WECHAT,
                note = null,
                reason = "补记一元",
            )
            val applied = reopened.receiptRepository.correctReceipt(amend)
            assertFalse(applied.alreadyApplied)
            assertTrue(reopened.receiptRepository.correctReceipt(amend).alreadyApplied)
            assertEquals(1, reopened.database.receiptDao().findAllCorrections().size)
        } finally {
            reopened.close()
        }
        context.deleteDatabase(name)
        Unit
    }

    @Test
    fun screensAgreeOnTheSameFinancialSnapshot() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createShortOrder(
            shortDraft(owner.id, account.id, priceYuan = "100.00")
                .copy(paymentMode = PaymentMode.INSTALLMENTS),
        )
        env.receiptRepository.recordPayment(payment(orderId, 2_500, "cross-screen"))

        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        val history = env.receiptRepository.observeReceiptHistory().first()
        val statistics = env.statisticsRepository.loadStatistics(
            StatisticsRange.ALL,
            ZoneId.of("Asia/Shanghai"),
            env.clock.now(),
        )

        assertEquals(2_500L, detail.balance().totalPaidCents)
        assertEquals(2_500L, history.single().receipt.amountCents)
        assertEquals(2_500L, statistics.receivedCents)
        assertEquals(detail.balance().totalOutstandingCents, statistics.outstandingCents)
        assertEquals(7_500L, detail.balance().totalOutstandingCents)
        assertEquals(1, statistics.orderCount)
        assertEquals(1, statistics.shortOrderCount)
        assertEquals(0, statistics.hostingOrderCount)
    }

    @Test
    fun hostingAndShortCountsStaySeparateAndHostingUsesItsExplicitPeriod() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val day = LocalDate.of(2026, 10, 17)
        val hosting = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, day, day.plusDays(9))
                .copy(paymentMode = PaymentMode.INSTALLMENTS),
        )
        val short = env.orderRepository.createShortOrder(
            shortDraft(owner.id, account.id).copy(paymentMode = PaymentMode.INSTALLMENTS),
        )
        val period = env.database.orderDao().findPeriods(hosting).single()
        env.receiptRepository.recordPayment(payment(hosting, 5_000, "hosting-p", period.id))
        env.receiptRepository.recordPayment(payment(short, 5_000, "short-p"))

        val statistics = env.statisticsRepository.loadStatistics(
            StatisticsRange.MONTH,
            ZoneId.of("Asia/Shanghai"),
            env.clock.now(),
        )
        assertEquals(2, statistics.orderCount)
        assertEquals(1, statistics.hostingOrderCount)
        assertEquals(1, statistics.shortOrderCount)
        assertEquals(10_000L, statistics.receivedCents)
        assertEquals(30_000L, statistics.outstandingCents)
        assertEquals(2, statistics.outstandingOrderCount)
        assertEquals(OrderType.HOSTING, statistics.outstandingItems.first { it.orderId == hosting }.orderType)
    }

    private fun payment(
        orderId: String,
        amountCents: Long,
        operationId: String,
        periodId: String? = null,
        receivedAt: Instant = env.clock.now(),
    ): PaymentRequest = PaymentRequest(
        orderId = orderId,
        periodId = periodId,
        amountCents = amountCents,
        receivedAt = receivedAt,
        channel = PaymentChannel.WECHAT,
        note = null,
        operationId = operationId,
    )
}
