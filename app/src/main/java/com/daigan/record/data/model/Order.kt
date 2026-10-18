package com.daigan.record.data.model

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class OrderType { SHORT_TASK, HOSTING }

/**
 * Captured at creation. Phase 2 never records receipts; the value is shown so the
 * settlement choice survives. Payment recording is explicitly pending Phase 3.
 */
enum class PaymentMode { ONE_TIME, INSTALLMENTS }

enum class ProgressType { CHECKBOX, NUMERIC }

enum class RecurrenceType { DAILY, WEEKLY, SELECTED_WEEKDAYS, EVERY_N_DAYS }

enum class BatchScope { HOSTING_ORDER, HOSTING_ALL, SHORT_ORDER }

enum class BatchItemType { OCCURRENCE, SHORT_TASK }

data class Order(
    val id: String,
    val accountId: String,
    val type: OrderType,
    val title: String,
    /** Negotiated total for a short order. Hosting prices live on each period. */
    val priceCents: Long?,
    val paymentMode: PaymentMode,
    val deadlineDate: LocalDate?,
    val notes: String?,
    val refresh: GameRefreshRulesDomain,
    val createdAt: Instant,
    val completedAt: Instant?,
) {
    val completed: Boolean get() = completedAt != null
    val isHosting: Boolean get() = type == OrderType.HOSTING
    val isShort: Boolean get() = type == OrderType.SHORT_TASK
}

/** Domain copy of [com.daigan.record.core.time.GameRefreshRules] without a UI dependency. */
data class GameRefreshRulesDomain(
    val zoneId: ZoneId,
    val dailyRefresh: LocalTime,
    val weeklyRefreshDay: DayOfWeek,
    val weeklyRefreshTime: LocalTime,
) {
    val dailyRefreshMinutes: Int get() = dailyRefresh.hour * 60 + dailyRefresh.minute
    val weeklyRefreshDayIso: Int get() = weeklyRefreshDay.value
    val weeklyRefreshMinutes: Int get() = weeklyRefreshTime.hour * 60 + weeklyRefreshTime.minute
}

data class HostingPeriod(
    val id: String,
    val orderId: String,
    val periodIndex: Int,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val priceCents: Long,
    val refresh: GameRefreshRulesDomain,
    val createdAt: Instant,
    val renewalOperationId: String?,
) {
    val durationDays: Int
        get() = (endDate.toEpochDay() - startDate.toEpochDay()).toInt() + 1
}

data class OrderTask(
    val id: String,
    val orderId: String,
    /** Null for short-order tasks; hosting tasks always belong to one period snapshot. */
    val periodId: String?,
    val title: String,
    val progressType: ProgressType,
    val startValue: BigDecimal,
    val targetValue: BigDecimal,
    val currentValue: BigDecimal,
    val unit: String?,
    val recurrence: RecurrenceType,
    val weekdays: Set<DayOfWeek>,
    val everyNDays: Int?,
    val sortOrder: Int,
    val completedAt: Instant?,
) {
    val completed: Boolean get() = completedAt != null

    /** Numeric tasks complete when the recorded value reaches the target. */
    val reachedTarget: Boolean get() = currentValue >= targetValue
}

data class TaskOccurrence(
    val id: String,
    val taskDefinitionId: String,
    val occurrenceKey: String,
    val attributedDate: LocalDate,
    val windowStart: LocalDate?,
    val windowEnd: LocalDate?,
    val completedAt: Instant?,
    val recordedAt: Instant?,
    val backfilled: Boolean,
    val completionBatchId: String?,
    val value: BigDecimal?,
    val createdAt: Instant,
) {
    val completed: Boolean get() = completedAt != null
}

/** Snapshot of the currently actionable slot for a task. */
data class OccurrenceSlot(
    val key: String,
    val attributedDate: LocalDate,
    val windowStart: LocalDate,
    val windowEnd: LocalDate,
    /**
     * Unclamped weekly window identity. Weekly windows are identified by their real
     * refresh date; display dates may be clamped to the hosting period.
     */
    val weeklyWindowStart: LocalDate? = null,
)

data class ShortWorkTask(
    val task: OrderTask,
    val completed: Boolean,
    val currentValue: BigDecimal,
)

data class ShortWorkOrder(
    val order: Order,
    val ownerId: String,
    val ownerNumber: String,
    val ownerNickname: String,
    val accountId: String,
    val gameName: String,
    val server: String,
    val tasks: List<ShortWorkTask>,
    val today: LocalDate,
) {
    val overdue: Boolean
        get() = order.deadlineDate?.isBefore(today) == true

    val allCompleted: Boolean get() = tasks.isNotEmpty() && tasks.all { it.completed }
}

data class TodayHostingTask(
    val task: OrderTask,
    val occurrenceId: String?,
    val slot: OccurrenceSlot,
    val completed: Boolean,
    val value: BigDecimal?,
    val windowLabel: String?,
)

data class TodayHostingOrder(
    val order: Order,
    val ownerId: String,
    val ownerNumber: String,
    val ownerNickname: String,
    val accountId: String,
    val gameName: String,
    val server: String,
    val period: HostingPeriod,
    val today: LocalDate,
    val status: com.daigan.record.core.time.HostingStatus,
    val tasks: List<TodayHostingTask>,
) {
    val allTasksCompleted: Boolean get() = tasks.isNotEmpty() && tasks.all { it.completed }
    val hasActionableTasks: Boolean get() = tasks.isNotEmpty()
    val expired: Boolean get() = status == com.daigan.record.core.time.HostingStatus.EXPIRED
}

data class OrderSummary(
    val order: Order,
    val ownerId: String,
    val ownerNumber: String,
    val ownerNickname: String,
    val accountId: String,
    val gameName: String,
    val server: String,
    val currentPeriod: HostingPeriod?,
    val taskCount: Int,
    val completedTaskCount: Int,
) {
    val accountLabel: String get() = "$gameName · $server"
}

data class OrderHistoryEntry(
    val periodIndex: Int?,
    val periodId: String?,
    val taskId: String,
    val occurrenceId: String?,
    val taskTitle: String,
    val attributedDate: LocalDate,
    val windowStart: LocalDate?,
    val windowEnd: LocalDate?,
    val completedAt: Instant,
    val recordedAt: Instant,
    val backfilled: Boolean,
    val value: BigDecimal?,
    val attachments: List<Attachment>,
)

data class Attachment(
    val id: String,
    val taskDefinitionId: String?,
    val occurrenceId: String?,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Instant,
)

data class OrderDetail(
    val order: Order,
    val owner: Owner,
    val account: GameAccount,
    /** Short tasks (periodId null) and every host period's task snapshots. */
    val tasks: List<OrderTask>,
    /** Hosting periods, newest first. Empty for short orders. */
    val periods: List<HostingPeriod>,
    /** Every persisted occurrence for this order. */
    val occurrences: List<TaskOccurrence>,
    val attachments: List<Attachment>,
    /** Every receipt, including voided and superseded rows, newest actual time first. */
    val receipts: List<Receipt>,
    val receiptCorrections: List<ReceiptCorrectionRecord>,
    val today: LocalDate,
    /** Clock instant used to resolve the current weekly window. */
    val now: Instant,
) {
    val currentPeriod: HostingPeriod? get() = periods.firstOrNull()
    val isHosting: Boolean get() = order.isHosting

    /** Paid and outstanding are always derived from prices and effective receipts. */
    fun balance(): OrderBalance = Balances.order(order, periods, receipts)

    fun receiptsForPeriod(periodId: String?): List<Receipt> =
        receipts.filter { it.periodId == periodId }

    fun correctionFor(receiptId: String): ReceiptCorrectionRecord? =
        receiptCorrections.filter { it.receiptId == receiptId }
            .maxByOrNull { it.createdAt }

    fun tasksForPeriod(periodId: String?): List<OrderTask> =
        tasks.filter { it.periodId == periodId }.sortedBy { it.sortOrder }

    fun occurrencesForTask(taskId: String): List<TaskOccurrence> =
        occurrences.filter { it.taskDefinitionId == taskId }

    fun attachmentsForTask(taskId: String): List<Attachment> =
        attachments.filter { it.taskDefinitionId == taskId }

    fun attachmentsForOccurrence(occurrenceId: String): List<Attachment> =
        attachments.filter { it.occurrenceId == occurrenceId }

    fun currentStatus(): com.daigan.record.core.time.HostingStatus? {
        val period = currentPeriod ?: return null
        return com.daigan.record.core.time.GameCalendar.hostingStatus(today, period.startDate, period.endDate)
    }

    /** Current actionable hosting tasks, resolved with the same rules as today's page. */
    fun currentHostingTasks(): List<TodayHostingTask> {
        val period = currentPeriod ?: return emptyList()
        if (currentStatus() != com.daigan.record.core.time.HostingStatus.ACTIVE) return emptyList()
        val rules = period.refresh.toCoreForRules()
        val occurrencesByKey = occurrences.associateBy { it.taskDefinitionId to it.occurrenceKey }
        return tasksForPeriod(period.id).mapNotNull { task ->
            val slot = OccurrenceRules.slotForNow(
                recurrence = task.recurrence,
                weekdays = task.weekdays,
                everyNDays = task.everyNDays,
                periodStart = period.startDate,
                periodEnd = period.endDate,
                today = today,
                rules = rules,
                now = now,
            ) ?: return@mapNotNull null
            val occurrence = occurrencesByKey[task.id to slot.key]
            TodayHostingTask(
                task = task,
                occurrenceId = occurrence?.id,
                slot = slot,
                completed = occurrence?.completedAt != null,
                value = occurrence?.value,
                windowLabel = OccurrenceRules.windowLabel(slot),
            )
        }
    }

    /** Completion history, newest first. Weekly entries keep their window label. */
    fun historyEntries(): List<OrderHistoryEntry> {
        val tasksById = tasks.associateBy { it.id }
        val periodsById = periods.associateBy { it.id }
        val occurrenceEntries = occurrences.mapNotNull { occurrence ->
            if (occurrence.completedAt == null) return@mapNotNull null
            val task = tasksById[occurrence.taskDefinitionId] ?: return@mapNotNull null
            val period = task.periodId?.let { periodsById[it] }
            OrderHistoryEntry(
                periodIndex = period?.periodIndex,
                periodId = period?.id,
                taskId = task.id,
                occurrenceId = occurrence.id,
                taskTitle = task.title,
                attributedDate = occurrence.attributedDate,
                windowStart = occurrence.windowStart,
                windowEnd = occurrence.windowEnd,
                completedAt = occurrence.completedAt,
                recordedAt = occurrence.recordedAt ?: occurrence.completedAt,
                backfilled = occurrence.backfilled,
                value = occurrence.value,
                attachments = attachments.filter { it.occurrenceId == occurrence.id },
            )
        }
        val shortEntries = tasks.mapNotNull { task ->
            val completedAt = task.completedAt ?: return@mapNotNull null
            OrderHistoryEntry(
                periodIndex = null,
                periodId = null,
                taskId = task.id,
                occurrenceId = null,
                taskTitle = task.title,
                attributedDate = today,
                windowStart = null,
                windowEnd = null,
                completedAt = completedAt,
                recordedAt = completedAt,
                backfilled = false,
                value = task.currentValue,
                attachments = attachments.filter { it.taskDefinitionId == task.id },
            )
        }
        return (occurrenceEntries + shortEntries).sortedByDescending { it.completedAt }
    }
}

private fun GameRefreshRulesDomain.toCoreForRules(): com.daigan.record.core.time.GameRefreshRules =
    com.daigan.record.core.time.GameRefreshRules(
        zoneId = zoneId,
        dailyRefresh = dailyRefresh,
        weeklyRefreshDay = weeklyRefreshDay,
        weeklyRefreshTime = weeklyRefreshTime,
    )

data class UndoConflict(
    val description: String,
    val reason: String,
)

data class BulkCompletionResult(
    val batchId: String,
    val completedCount: Int,
    val skippedCount: Int,
    val alreadyApplied: Boolean,
)

data class UndoResult(
    val restoredCount: Int,
    val conflicts: List<UndoConflict>,
    val alreadyUndone: Boolean,
)

data class RenewalResult(
    val periodId: String,
    val periodIndex: Int,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val alreadyApplied: Boolean,
)
