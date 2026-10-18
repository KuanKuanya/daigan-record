package com.daigan.record.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.daigan.record.data.model.BatchItemType
import com.daigan.record.data.model.BatchScope
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * An order belongs to exactly one game account. Renewal never changes the order id;
 * a hosting order owns a sequence of [HostingPeriodEntity] snapshots instead.
 *
 * [priceCents] is the negotiated total for a short order and null for hosting orders,
 * where each period stores its own price. Short orders keep [completedAt] in sync with
 * their tasks; hosting completion lives on occurrence records.
 */
@Entity(
    tableName = "orders",
    foreignKeys = [
        ForeignKey(
            entity = GameAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["accountId"], name = "index_orders_accountId"),
        Index(value = ["accountId", "type"], name = "index_orders_accountId_type"),
        Index(value = ["type", "completedAt"], name = "index_orders_type_completedAt"),
    ],
)
data class OrderEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val type: OrderType,
    val title: String,
    val priceCents: Long?,
    val paymentMode: PaymentMode,
    val deadlineDate: LocalDate?,
    val notes: String?,
    val gameZoneId: String,
    val dailyRefreshMinutes: Int,
    val weeklyRefreshDay: Int,
    val weeklyRefreshMinutes: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
)

/**
 * One hosting period. The price and refresh rules are immutable snapshots: a later
 * order-level change or a renewal must never rewrite an older period's terms.
 */
@Entity(
    tableName = "hosting_periods",
    foreignKeys = [
        ForeignKey(
            entity = OrderEntity::class,
            parentColumns = ["id"],
            childColumns = ["orderId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["orderId"], name = "index_hosting_periods_orderId"),
        Index(
            value = ["orderId", "periodIndex"],
            unique = true,
            name = "index_hosting_periods_orderId_periodIndex",
        ),
        Index(
            value = ["orderId", "startDate"],
            unique = true,
            name = "index_hosting_periods_orderId_startDate",
        ),
        Index(
            value = ["renewalOperationId"],
            unique = true,
            name = "index_hosting_periods_renewalOperationId",
        ),
    ],
)
data class HostingPeriodEntity(
    @PrimaryKey val id: String,
    val orderId: String,
    val periodIndex: Int,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val priceCents: Long,
    val gameZoneId: String,
    val dailyRefreshMinutes: Int,
    val weeklyRefreshDay: Int,
    val weeklyRefreshMinutes: Int,
    val createdAt: Instant,
    val renewalOperationId: String?,
)

/**
 * A task definition snapshot. Short-order tasks have a null [periodId]; every hosting
 * period gets independent task rows, so renewal never mutates the previous period.
 */
@Entity(
    tableName = "task_definitions",
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
        ForeignKey(
            entity = CompletionBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["completionBatchId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["orderId"], name = "index_task_definitions_orderId"),
        Index(value = ["periodId"], name = "index_task_definitions_periodId"),
        Index(value = ["completionBatchId"], name = "index_task_definitions_completionBatchId"),
    ],
)
data class TaskDefinitionEntity(
    @PrimaryKey val id: String,
    val orderId: String,
    val periodId: String?,
    val title: String,
    val progressType: ProgressType,
    val startValue: BigDecimal,
    val targetValue: BigDecimal,
    val currentValue: BigDecimal,
    val unit: String?,
    val recurrence: RecurrenceType,
    /** ISO day-of-week numbers (1 = Monday) joined with commas; null when unused. */
    val weekdays: String?,
    val everyNDays: Int?,
    val sortOrder: Int,
    val completedAt: Instant?,
    val completionBatchId: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * One persisted occurrence. The unique `(taskDefinitionId, occurrenceKey)` index makes
 * repeated generation safe: a daily task key includes the game date, a weekly task key
 * includes the weekly window start, so tapping twice or reloading cannot duplicate work.
 */
@Entity(
    tableName = "task_occurrences",
    foreignKeys = [
        ForeignKey(
            entity = TaskDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskDefinitionId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CompletionBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["completionBatchId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["taskDefinitionId"], name = "index_task_occurrences_taskDefinitionId"),
        Index(
            value = ["taskDefinitionId", "occurrenceKey"],
            unique = true,
            name = "index_task_occurrences_taskDefinitionId_occurrenceKey",
        ),
        Index(value = ["gameDate"], name = "index_task_occurrences_gameDate"),
        Index(value = ["completionBatchId"], name = "index_task_occurrences_completionBatchId"),
    ],
)
data class TaskOccurrenceEntity(
    @PrimaryKey val id: String,
    val taskDefinitionId: String,
    val occurrenceKey: String,
    /** Game date for daily/weekday/every-N tasks; window start for weekly tasks. */
    val gameDate: LocalDate,
    val windowStart: LocalDate?,
    val windowEnd: LocalDate?,
    val completedAt: Instant?,
    val recordedAt: Instant?,
    val backfilled: Boolean,
    val completionBatchId: String?,
    val value: BigDecimal?,
    val createdAt: Instant,
)

/** Audit header for a bulk completion. [operationId] makes retries idempotent. */
@Entity(
    tableName = "completion_batches",
    indices = [
        Index(
            value = ["operationId"],
            unique = true,
            name = "index_completion_batches_operationId",
        ),
    ],
)
data class CompletionBatchEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val scope: BatchScope,
    val targetId: String?,
    val gameDate: LocalDate?,
    val createdAt: Instant,
    val undoneAt: Instant?,
)

/**
 * Exact per-entity change set of a batch. Undo compares the *applied* values with the
 * current row; a later independent edit is a conflict and is never overwritten.
 */
@Entity(
    tableName = "completion_batch_items",
    foreignKeys = [
        ForeignKey(
            entity = CompletionBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TaskOccurrenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["occurrenceId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TaskDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskDefinitionId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["batchId"], name = "index_completion_batch_items_batchId"),
        Index(value = ["occurrenceId"], name = "index_completion_batch_items_occurrenceId"),
        Index(
            value = ["taskDefinitionId"],
            name = "index_completion_batch_items_taskDefinitionId",
        ),
    ],
)
data class CompletionBatchItemEntity(
    @PrimaryKey val id: String,
    val batchId: String,
    val itemType: BatchItemType,
    val occurrenceId: String?,
    val taskDefinitionId: String?,
    val previousCompletedAt: Instant?,
    val previousRecordedAt: Instant?,
    val previousBackfilled: Boolean?,
    val previousValue: BigDecimal?,
    val previousCompletionBatchId: String?,
    val appliedCompletedAt: Instant?,
    val appliedRecordedAt: Instant?,
    val appliedBackfilled: Boolean?,
    val appliedValue: BigDecimal?,
    val appliedCompletionBatchId: String?,
)

/**
 * Screenshot metadata. The image itself lives in application-private storage; only the
 * relative path is stored, never Base64. Exactly one of the two owner columns is set.
 */
@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = TaskDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskDefinitionId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TaskOccurrenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["occurrenceId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["taskDefinitionId"],
            name = "index_attachments_taskDefinitionId",
        ),
        Index(value = ["occurrenceId"], name = "index_attachments_occurrenceId"),
    ],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val taskDefinitionId: String?,
    val occurrenceId: String?,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Instant,
)
