package com.daigan.record.review

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.InitialPaymentDraft
import com.daigan.record.data.model.PaymentChannel
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.PaymentRequest
import com.daigan.record.data.model.ReceiptCorrectionRequest
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.hostingDraft
import com.daigan.record.testing.shortDraft
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Independent Phase 3 acceptance contracts; failures intentionally retained for repair. */
@RunWith(AndroidJUnit4::class)
class Phase3AcceptanceReviewTest {
    private lateinit var env: OrderTestEnvironment

    @Before fun setUp() {
        env = OrderTestEnvironment(ApplicationProvider.getApplicationContext<Context>())
    }

    @After fun tearDown() { env.close() }

    @Test fun retryInitiallyPaidShortOrderReturnsOriginalOrder() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val draft = shortDraft(owner.id, account.id).copy(
            initialPayment = InitialPaymentDraft("100.00", operationId = "initial-short"),
        )
        val first = env.orderRepository.createShortOrder(draft)
        val retry = runCatching { env.orderRepository.createShortOrder(draft) }
        assertTrue("Identical retry must return original order; actual: ${retry.exceptionOrNull()}", retry.isSuccess)
        assertEquals(first, retry.getOrThrow())
        assertEquals(1, env.database.orderDao().countForAccount(account.id))
        assertEquals(1, env.database.receiptDao().findAll().size)
    }

    @Test fun retryInitiallyPaidHostingOrderReturnsOriginalOrder() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val day = LocalDate.of(2026, 10, 17)
        val draft = hostingDraft(owner.id, account.id, day, day.plusDays(29)).copy(
            initialPayment = InitialPaymentDraft("300.00", operationId = "initial-hosting"),
        )
        val first = env.orderRepository.createHostingOrder(draft)
        val retry = runCatching { env.orderRepository.createHostingOrder(draft) }
        assertTrue("Identical retry must return original order; actual: ${retry.exceptionOrNull()}", retry.isSuccess)
        assertEquals(first, retry.getOrThrow())
        assertEquals(1, env.database.orderDao().countForAccount(account.id))
    }

    @Test fun correctionOperationIdCannotBeReusedWithDifferentAuditReason() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val id = env.orderRepository.createShortOrder(shortDraft(owner.id, account.id))
        val paid = env.receiptRepository.recordPayment(
            PaymentRequest(id, null, 10_000, env.clock.now(), PaymentChannel.WECHAT, null, "payment"),
        )
        val request = ReceiptCorrectionRequest.Void("void-op", paid.receipt.id, "录错了")
        env.receiptRepository.correctReceipt(request)
        val conflicting = runCatching {
            env.receiptRepository.correctReceipt(request.copy(reason = "另一种原因"))
        }
        assertTrue("Conflicting operation-id reuse must be rejected, including changed audit reason", conflicting.isFailure)
    }

    @Test fun receiptRetryPreservesTimestampWithSubMillisecondClock() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val id = env.orderRepository.createShortOrder(shortDraft(owner.id, account.id))
        val instant = env.clock.now().minusSeconds(1).plusNanos(123456)
        val request = PaymentRequest(id, null, 10_000, instant, PaymentChannel.WECHAT, null, "precision")
        env.receiptRepository.recordPayment(request)
        val retry = runCatching { env.receiptRepository.recordPayment(request) }
        assertTrue("Identical Instant request must remain idempotent after Room serialization: ${retry.exceptionOrNull()}", retry.isSuccess)
        assertTrue(retry.getOrThrow().alreadyApplied)
    }

    @Test fun normalPartialSettlementAndRetryDoNotDuplicateMoney() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val id = env.orderRepository.createShortOrder(
            shortDraft(owner.id, account.id).copy(paymentMode = PaymentMode.INSTALLMENTS),
        )
        val request = PaymentRequest(id, null, 4_000, env.clock.now(), PaymentChannel.ALIPAY, null, "partial")
        env.receiptRepository.recordPayment(request)
        assertTrue(env.receiptRepository.recordPayment(request).alreadyApplied)
        val receipts = env.database.receiptDao().findAll()
        assertEquals(1, receipts.size)
        assertEquals(4_000L, receipts.single().amountCents)
        val excess = runCatching {
            env.receiptRepository.recordPayment(request.copy(amountCents = 6_001, operationId = "overpay"))
        }
        assertTrue(excess.isFailure)
    }
}
