package com.kuankuan.daigan.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.kuankuan.daigan.core.time.PersistedInstant
import com.kuankuan.daigan.core.time.TimeProvider
import com.kuankuan.daigan.data.local.dao.OrderDao
import com.kuankuan.daigan.data.local.dao.ReceiptDao
import com.kuankuan.daigan.data.local.entity.ReceiptCorrectionEntity
import com.kuankuan.daigan.data.local.entity.ReceiptEntity
import com.kuankuan.daigan.data.local.toModel
import com.kuankuan.daigan.data.model.CorrectionResult
import com.kuankuan.daigan.data.model.Money
import com.kuankuan.daigan.data.model.OrderCommandFingerprint
import com.kuankuan.daigan.data.model.OrderType
import com.kuankuan.daigan.data.model.PaymentChannel
import com.kuankuan.daigan.data.model.PaymentMode
import com.kuankuan.daigan.data.model.PaymentRequest
import com.kuankuan.daigan.data.model.PaymentResult
import com.kuankuan.daigan.data.model.RECEIPT_NOTE_MAX_LENGTH
import com.kuankuan.daigan.data.model.RECEIPT_REASON_MAX_LENGTH
import com.kuankuan.daigan.data.model.Receipt
import com.kuankuan.daigan.data.model.ReceiptCorrectionKind
import com.kuankuan.daigan.data.model.ReceiptCorrectionRequest
import com.kuankuan.daigan.data.model.ReceiptStatus
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single write gateway for money.
 *
 * Both order creation (optional initial receipt) and payment entry call these methods
 * *inside* an existing `database.withTransaction` block, so the balance read, the
 * operation-id check and the insert are one atomic unit. A failed payment therefore
 * rolls back a just-created order or period, and two concurrent requests cannot both
 * pass the balance check.
 *
 * Archive policy: this class never calls the Phase 2 archive write guards. Settling an
 * archived owner's debt is explicitly allowed and touches only receipt rows, so no
 * archived task or order is reactivated.
 */
@Singleton
class ReceiptLedger @Inject constructor(
    private val receiptDao: ReceiptDao,
    private val orderDao: OrderDao,
    private val timeProvider: TimeProvider,
) {

    suspend fun record(request: PaymentRequest): PaymentResult = record(request, timeProvider.now())

    suspend fun record(request: PaymentRequest, now: Instant): PaymentResult {
        // Normalize to the precision Room actually persists *before* validating and
        // comparing. A sub-millisecond Instant must not become a false conflict on
        // retry, even when the caller bypasses the dialog's millisecond conversion.
        val recordedAt = PersistedInstant.normalize(now)
        val command = request.normalizedPayment()
        require(request.operationId.isNotBlank()) { "操作标识不能为空" }
        require(command.amountCents > 0) { "收款金额必须大于 0" }
        require(command.amountCents <= Money.MAX_CENTS) { "金额过大" }
        require(!command.receivedAt.isAfter(recordedAt)) { "收款时间不能晚于当前时间" }

        // Idempotency comes before the balance check: a retry of a successful payment
        // must return the original receipt even though the item is now fully settled.
        receiptDao.findByOperationId(request.operationId)?.let { existing ->
            if (existing.normalizedPayment() == command) {
                return PaymentResult(existing.toModel(), alreadyApplied = true)
            }
            throw IllegalStateException("该操作标识已被另一笔收款使用，请重新提交")
        }

        val order = orderDao.findById(request.orderId) ?: throw IllegalArgumentException("订单不存在")
        require(order.cancelledAt == null) { "该订单已作废，不能新增收款；已有流水仍可更正或作废" }
        val priceCents = when (order.type) {
            OrderType.SHORT_TASK -> {
                require(request.periodId == null) { "短期订单的收款不能指定托管期次" }
                order.priceCents ?: 0L
            }

            OrderType.HOSTING -> {
                val periodId = request.periodId
                    ?: throw IllegalArgumentException("请选择本次收款归属的托管期次")
                val period = orderDao.findPeriod(periodId)
                    ?: throw IllegalArgumentException("托管期次不存在")
                require(period.orderId == order.id) { "所选期次不属于该订单" }
                period.priceCents
            }
        }

        val paid = effectivePaidCents(request.orderId, request.periodId, excludingReceiptId = null)
        val remaining = Money.remainingCents(priceCents, paid)
        require(remaining > 0) { "该款项已经结清，无需再次收款" }
        require(request.amountCents <= remaining) {
            "收款金额超过待收余额 ${Money.formatCents(remaining)}"
        }
        if (order.paymentMode == PaymentMode.ONE_TIME) {
            require(request.amountCents == remaining) {
                "一次结清需收齐全部剩余金额 ${Money.formatCents(remaining)}"
            }
        }

        val entity = ReceiptEntity(
            id = UUID.randomUUID().toString(),
            operationId = request.operationId,
            orderId = command.orderId,
            periodId = command.periodId,
            amountCents = command.amountCents,
            receivedAt = command.receivedAt,
            channel = command.channel,
            note = command.note,
            status = ReceiptStatus.ACTIVE,
            replacedByReceiptId = null,
            createdAt = recordedAt,
        )
        try {
            receiptDao.insert(entity)
        } catch (_: SQLiteConstraintException) {
            // A concurrent request won the same operation id. Return its result if it
            // is the same request, otherwise report the conflicting reuse.
            val existing = receiptDao.findByOperationId(request.operationId)
                ?: throw IllegalStateException("收款写入冲突，请重试")
            if (existing.normalizedPayment() == command) {
                return PaymentResult(existing.toModel(), alreadyApplied = true)
            }
            throw IllegalStateException("该操作标识已被另一笔收款使用，请重新提交")
        }
        return PaymentResult(entity.toModel(), alreadyApplied = false)
    }

    suspend fun correct(request: ReceiptCorrectionRequest): CorrectionResult =
        correct(request, timeProvider.now())

    suspend fun correct(request: ReceiptCorrectionRequest, now: Instant): CorrectionResult {
        val recordedAt = PersistedInstant.normalize(now)
        require(request.operationId.isNotBlank()) { "操作标识不能为空" }
        val command = request.normalizedCorrection()
        require((command.reason?.length ?: 0) <= RECEIPT_REASON_MAX_LENGTH) {
            "更正原因最多 $RECEIPT_REASON_MAX_LENGTH 个字符"
        }

        receiptDao.findCorrectionByOperationId(request.operationId)?.let { existing ->
            if (existing.normalizedCorrection() == command) {
                val receipt = existing.newReceiptId?.let { receiptDao.findById(it)?.toModel() }
                return CorrectionResult(receipt, existing.toModel(), alreadyApplied = true)
            }
            throw IllegalStateException("该操作标识已被另一次更正使用，请重新提交")
        }

        val original = receiptDao.findById(request.receiptId)
            ?: throw IllegalArgumentException("收款记录不存在")
        require(original.status == ReceiptStatus.ACTIVE) {
            "该收款已作废或已更正，不能再次处理"
        }

        return when (request) {
            is ReceiptCorrectionRequest.Void ->
                void(original, command.reason, request.operationId, recordedAt)

            is ReceiptCorrectionRequest.Amend ->
                amend(original, command, request.operationId, recordedAt)
        }
    }

    private suspend fun void(
        original: ReceiptEntity,
        reason: String?,
        operationId: String,
        recordedAt: Instant,
    ): CorrectionResult {
        val updated = receiptDao.markInactive(original.id, ReceiptStatus.VOIDED, null)
        require(updated == 1) { "该收款已被其他更正处理，请刷新后重试" }
        val correction = ReceiptCorrectionEntity(
            id = UUID.randomUUID().toString(),
            operationId = operationId,
            receiptId = original.id,
            kind = ReceiptCorrectionKind.VOID,
            reason = reason,
            previousAmountCents = original.amountCents,
            previousReceivedAt = original.receivedAt,
            previousChannel = original.channel,
            previousNote = original.note,
            newReceiptId = null,
            newAmountCents = null,
            newReceivedAt = null,
            newChannel = null,
            newNote = null,
            createdAt = recordedAt,
        )
        receiptDao.insertCorrection(correction)
        return CorrectionResult(receipt = null, correction = correction.toModel(), alreadyApplied = false)
    }

    private suspend fun amend(
        original: ReceiptEntity,
        command: NormalizedCorrection,
        operationId: String,
        recordedAt: Instant,
    ): CorrectionResult {
        val amountCents = requireNotNull(command.amountCents) { "缺少更正金额" }
        val receivedAt = requireNotNull(command.receivedAt) { "缺少更正后的收款时间" }
        val channel = requireNotNull(command.channel) { "缺少更正后的收款渠道" }
        require(amountCents > 0) { "更正后的金额必须大于 0" }
        require(amountCents <= Money.MAX_CENTS) { "金额过大" }
        require(!receivedAt.isAfter(recordedAt)) { "收款时间不能晚于当前时间" }

        val order = orderDao.findById(original.orderId)
            ?: throw IllegalArgumentException("订单不存在")
        val priceCents = when (order.type) {
            OrderType.SHORT_TASK -> order.priceCents ?: 0L
            OrderType.HOSTING -> {
                val periodId = original.periodId
                    ?: throw IllegalStateException("托管收款缺少期次，无法更正")
                orderDao.findPeriod(periodId)?.priceCents
                    ?: throw IllegalArgumentException("托管期次不存在")
            }
        }
        val otherPaid = effectivePaidCents(
            orderId = original.orderId,
            periodId = original.periodId,
            excludingReceiptId = original.id,
        )
        val remaining = Money.remainingCents(priceCents, otherPaid)
        require(amountCents <= remaining) {
            "更正后的金额超过该款项可收余额 ${Money.formatCents(remaining)}"
        }

        val replacement = ReceiptEntity(
            id = UUID.randomUUID().toString(),
            // Derived, stable operation id for the replacement row. The correction's
            // own operation id remains the retry key.
            operationId = "$operationId:amended",
            orderId = original.orderId,
            periodId = original.periodId,
            amountCents = amountCents,
            receivedAt = receivedAt,
            channel = channel,
            note = command.note,
            status = ReceiptStatus.ACTIVE,
            replacedByReceiptId = null,
            createdAt = recordedAt,
        )
        receiptDao.insert(replacement)
        val updated = receiptDao.markInactive(original.id, ReceiptStatus.REPLACED, replacement.id)
        require(updated == 1) { "该收款已被其他更正处理，请刷新后重试" }

        val correction = ReceiptCorrectionEntity(
            id = UUID.randomUUID().toString(),
            operationId = operationId,
            receiptId = original.id,
            kind = ReceiptCorrectionKind.AMEND,
            reason = command.reason,
            previousAmountCents = original.amountCents,
            previousReceivedAt = original.receivedAt,
            previousChannel = original.channel,
            previousNote = original.note,
            newReceiptId = replacement.id,
            newAmountCents = replacement.amountCents,
            newReceivedAt = replacement.receivedAt,
            newChannel = replacement.channel,
            newNote = replacement.note,
            createdAt = recordedAt,
        )
        receiptDao.insertCorrection(correction)
        return CorrectionResult(
            receipt = replacement.toModel(),
            correction = correction.toModel(),
            alreadyApplied = false,
        )
    }

    private suspend fun effectivePaidCents(
        orderId: String,
        periodId: String?,
        excludingReceiptId: String?,
    ): Long = Money.sumCents(
        receiptDao.findForOrder(orderId)
            .filter {
                it.status == ReceiptStatus.ACTIVE &&
                    it.periodId == periodId &&
                    it.id != excludingReceiptId
            }
            .map { it.amountCents },
    )

    /**
     * The exact payload that is persisted and compared. [receivedAt] is normalized to
     * epoch milliseconds and notes follow the documented "其他"-only rule, so an
     * identical retry compares equal after a database round trip.
     */
    private data class NormalizedPayment(
        val orderId: String,
        val periodId: String?,
        val amountCents: Long,
        val receivedAt: Instant,
        val channel: PaymentChannel,
        val note: String?,
    )

    private fun PaymentRequest.normalizedPayment(): NormalizedPayment {
        val note = OrderCommandFingerprint.normalizedReceiptNote(channel, note)
        require((note?.length ?: 0) <= RECEIPT_NOTE_MAX_LENGTH) {
            "备注最多 $RECEIPT_NOTE_MAX_LENGTH 个字符"
        }
        return NormalizedPayment(
            orderId = orderId,
            periodId = periodId,
            amountCents = amountCents,
            receivedAt = PersistedInstant.normalize(receivedAt),
            channel = channel,
            note = note,
        )
    }

    private fun ReceiptEntity.normalizedPayment(): NormalizedPayment = NormalizedPayment(
        orderId = orderId,
        periodId = periodId,
        amountCents = amountCents,
        receivedAt = PersistedInstant.normalize(receivedAt),
        channel = channel,
        note = note,
    )

    /**
     * Every field of a correction participates in conflict detection, including the
     * audit reason. Comparing only the kind would silently accept a different payload
     * under an already-used operation id.
     */
    private data class NormalizedCorrection(
        val receiptId: String,
        val kind: ReceiptCorrectionKind,
        val reason: String?,
        val amountCents: Long?,
        val receivedAt: Instant?,
        val channel: PaymentChannel?,
        val note: String?,
    )

    private fun ReceiptCorrectionRequest.normalizedCorrection(): NormalizedCorrection =
        when (this) {
            is ReceiptCorrectionRequest.Void -> NormalizedCorrection(
                receiptId = receiptId,
                kind = ReceiptCorrectionKind.VOID,
                reason = OrderCommandFingerprint.normalizedText(reason),
                amountCents = null,
                receivedAt = null,
                channel = null,
                note = null,
            )

            is ReceiptCorrectionRequest.Amend -> NormalizedCorrection(
                receiptId = receiptId,
                kind = ReceiptCorrectionKind.AMEND,
                reason = OrderCommandFingerprint.normalizedText(reason),
                amountCents = amountCents,
                receivedAt = PersistedInstant.normalize(receivedAt),
                channel = channel,
                note = OrderCommandFingerprint.normalizedReceiptNote(channel, note),
            )
        }

    private fun ReceiptCorrectionEntity.normalizedCorrection(): NormalizedCorrection =
        NormalizedCorrection(
            receiptId = receiptId,
            kind = kind,
            reason = reason,
            amountCents = newAmountCents,
            receivedAt = newReceivedAt?.let(PersistedInstant::normalize),
            channel = newChannel,
            note = newNote,
        )

    /** Used by the order detail to explain a REPLACED row. */
    suspend fun correctionsFor(receiptId: String) =
        receiptDao.findCorrectionsForReceipt(receiptId)
}
