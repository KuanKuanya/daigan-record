package com.kuankuan.daigan.data.model

import java.time.Instant
import java.time.LocalDate

const val RECEIPT_NOTE_MAX_LENGTH = 120
const val RECEIPT_REASON_MAX_LENGTH = 200

/** The only accepted payment channels. A note is allowed for [OTHER]. */
enum class PaymentChannel(val label: String) {
    WECHAT("微信"),
    ALIPAY("支付宝"),
    OTHER("其他"),
    ;

    companion object {
        fun fromLabelOrName(raw: String?): PaymentChannel? =
            entries.firstOrNull { it.label == raw || it.name == raw }
    }
}

/**
 * A receipt is never deleted. A correction voids or replaces the row, and both states
 * stay visible in history. Only [ACTIVE] receipts count toward paid and outstanding.
 */
enum class ReceiptStatus { ACTIVE, VOIDED, REPLACED }

/**
 * One real money movement tied to an order and, for hosting orders, to exactly one
 * explicitly chosen period. [receivedAt] is the actual receipt moment entered by the
 * user; [createdAt] is when the row was recorded.
 */
data class Receipt(
    val id: String,
    val operationId: String,
    val orderId: String,
    /** Null only for short orders; hosting receipts always name their period. */
    val periodId: String?,
    val amountCents: Long,
    val receivedAt: Instant,
    val channel: PaymentChannel,
    val note: String?,
    val status: ReceiptStatus,
    val replacedByReceiptId: String?,
    val createdAt: Instant,
) {
    val effective: Boolean get() = status == ReceiptStatus.ACTIVE
    val corrected: Boolean get() = status == ReceiptStatus.REPLACED
    val voided: Boolean get() = status == ReceiptStatus.VOIDED
}

enum class ReceiptCorrectionKind { VOID, AMEND }

/**
 * Immutable audit row for a receipt correction. `previous*` records exactly what the
 * receipt looked like before the correction; `new*` records the replacement values
 * (null for a void). Money history is therefore never overwritten without a trace.
 */
data class ReceiptCorrectionRecord(
    val id: String,
    val operationId: String,
    val receiptId: String,
    val kind: ReceiptCorrectionKind,
    val reason: String?,
    val previousAmountCents: Long,
    val previousReceivedAt: Instant,
    val previousChannel: PaymentChannel,
    val previousNote: String?,
    val newReceiptId: String?,
    val newAmountCents: Long?,
    val newReceivedAt: Instant?,
    val newChannel: PaymentChannel?,
    val newNote: String?,
    val createdAt: Instant,
)

/** A payment request; [operationId] is stable across UI retries. */
data class PaymentRequest(
    val orderId: String,
    val periodId: String?,
    val amountCents: Long,
    val receivedAt: Instant,
    val channel: PaymentChannel,
    val note: String?,
    val operationId: String,
)

data class PaymentResult(
    val receipt: Receipt,
    val alreadyApplied: Boolean,
)

/** Correction commands. Both are idempotent through [operationId]. */
sealed interface ReceiptCorrectionRequest {
    val operationId: String
    val receiptId: String
    val reason: String?

    data class Void(
        override val operationId: String,
        override val receiptId: String,
        override val reason: String? = null,
    ) : ReceiptCorrectionRequest

    data class Amend(
        override val operationId: String,
        override val receiptId: String,
        val amountCents: Long,
        val receivedAt: Instant,
        val channel: PaymentChannel,
        val note: String?,
        override val reason: String? = null,
    ) : ReceiptCorrectionRequest
}

data class CorrectionResult(
    /** The effective receipt after the correction, or null for a void. */
    val receipt: Receipt?,
    val correction: ReceiptCorrectionRecord,
    val alreadyApplied: Boolean,
)

/**
 * Receipt row with the context the Settings history page needs. A replaced row keeps
 * its original amount; [correctedToCents] names the replacement amount when known.
 */
data class ReceiptHistoryEntry(
    val receipt: Receipt,
    val ownerId: String,
    val ownerNumber: String,
    val ownerNickname: String,
    val accountId: String,
    val gameName: String,
    val server: String,
    val orderTitle: String,
    val orderType: OrderType,
    val periodIndex: Int?,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    val correctedToCents: Long?,
) {
    val hosting: Boolean get() = orderType == OrderType.HOSTING

    val periodLabel: String
        get() = if (!hosting) {
            "短期订单"
        } else {
            "第 ${(periodIndex ?: 0) + 1} 期" + if (periodStart != null && periodEnd != null) {
                " · $periodStart – $periodEnd"
            } else {
                ""
            }
        }

    val ownerLabel: String get() = "$ownerNickname · $ownerNumber"
    val accountLabel: String get() = "$gameName · $server"
}
