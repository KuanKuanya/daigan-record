package com.daigan.record.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.daigan.record.data.model.PaymentChannel
import com.daigan.record.data.model.ReceiptCorrectionKind
import com.daigan.record.data.model.ReceiptStatus
import java.time.Instant

/**
 * Persistent receipts. Rows are append-only: a correction either voids the row or marks
 * it REPLACED and inserts the replacement, so the ledger always explains each amount.
 *
 * The unique `operationId` index is the idempotency guard for retried submissions.
 * `periodId` is null for short orders and mandatory for hosting orders (validated in
 * the ledger inside the same transaction as the balance check).
 */
@Entity(
    tableName = "receipts",
    foreignKeys = [
        ForeignKey(
            entity = OrderEntity::class,
            parentColumns = ["id"],
            childColumns = ["orderId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = HostingPeriodEntity::class,
            parentColumns = ["id"],
            childColumns = ["periodId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["operationId"], unique = true, name = "index_receipts_operationId"),
        Index(value = ["orderId"], name = "index_receipts_orderId"),
        Index(value = ["periodId"], name = "index_receipts_periodId"),
        Index(value = ["receivedAt"], name = "index_receipts_receivedAt"),
    ],
)
data class ReceiptEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val orderId: String,
    val periodId: String?,
    val amountCents: Long,
    val receivedAt: Instant,
    val channel: PaymentChannel,
    val note: String?,
    val status: ReceiptStatus,
    val replacedByReceiptId: String?,
    val createdAt: Instant,
)

/**
 * Audit trail for a void/amendment. `previous*` is the exact receipt snapshot before
 * the correction; `new*` is the replacement value (null for a void).
 */
@Entity(
    tableName = "receipt_corrections",
    foreignKeys = [
        ForeignKey(
            entity = ReceiptEntity::class,
            parentColumns = ["id"],
            childColumns = ["receiptId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ReceiptEntity::class,
            parentColumns = ["id"],
            childColumns = ["newReceiptId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["operationId"], unique = true, name = "index_receipt_corrections_operationId"),
        Index(value = ["receiptId"], name = "index_receipt_corrections_receiptId"),
        Index(value = ["newReceiptId"], name = "index_receipt_corrections_newReceiptId"),
    ],
)
data class ReceiptCorrectionEntity(
    @PrimaryKey val id: String,
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
