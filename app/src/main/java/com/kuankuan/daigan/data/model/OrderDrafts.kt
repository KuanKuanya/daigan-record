package com.kuankuan.daigan.data.model

import com.kuankuan.daigan.core.time.GameCalendar
import com.kuankuan.daigan.core.time.GameRefreshRules
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** One task row in the order-creation form. Values stay textual until submission. */
data class TaskDraft(
    val title: String = "",
    val progressType: ProgressType = ProgressType.CHECKBOX,
    val startValue: String = "0",
    val targetValue: String = "1",
    val unit: String = "",
    val recurrence: RecurrenceType = RecurrenceType.DAILY,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val everyNDays: String = "",
    val monthDay: String = "",
) {
    fun startDecimal(): BigDecimal? = parseDecimal(startValue)
    fun targetDecimal(): BigDecimal? = parseDecimal(targetValue)
    fun everyNOrNull(): Int? = everyNDays.trim().toIntOrNull()
    fun monthDayOrNull(): Int? = monthDay.trim().toIntOrNull()

    companion object {
        fun parseDecimal(raw: String): BigDecimal? =
            runCatching { BigDecimal(raw.trim()) }.getOrNull()
    }
}

/**
 * Optional first receipt captured together with order creation. [operationId] is
 * generated once by the ViewModel and reused on retry, so a resubmission or double tap
 * cannot create two payments. A blank amount means "no receipt".
 *
 * [receivedAt] is the **actual** moment the money arrived, which is deliberately
 * separate from the recording time. It may be a historical timestamp (for example an
 * order created in October whose first payment actually arrived in September) and is
 * normalized to the database's millisecond precision before it becomes part of the
 * creation command. `null` means "same as the creation time" and is kept stable for
 * retries without freezing an arbitrary wall-clock value into the command.
 */
data class InitialPaymentDraft(
    val amountYuan: String,
    val channel: PaymentChannel = PaymentChannel.WECHAT,
    val note: String = "",
    val operationId: String,
    val receivedAt: Instant? = null,
)

/**
 * Truthful snapshot of the template an order was created from. It is copied as plain
 * text at creation and never points at the editable template row, so editing, replacing
 * or deleting the template later cannot rewrite history. Legacy orders have null.
 */
data class OrderTemplateProvenance(
    val templateId: String,
    val gameName: String,
    val templateVersion: String,
)

data class ShortOrderDraft(
    val ownerId: String,
    val accountId: String,
    val title: String = "短期任务",
    val priceYuan: String = "",
    val paymentMode: PaymentMode = PaymentMode.ONE_TIME,
    val deadlineDate: LocalDate? = null,
    val notes: String = "",
    val tasks: List<TaskDraft> = listOf(TaskDraft()),
    val initialPayment: InitialPaymentDraft? = null,
    /** Null retains pre-calendar command semantics for legacy retries. */
    val refresh: GameRefreshRules? = null,
    /** Template the creation defaults came from; descriptive, never a live reference. */
    val sourceTemplate: OrderTemplateProvenance? = null,
    /**
     * Identity of the whole create command. Callers that retry must reuse the same
     * value; a genuinely new order must use a new one. The default is only a
     * convenience for tests and one-shot callers.
     */
    val creationOperationId: String = UUID.randomUUID().toString(),
) {
    val priceCentsOrNull: Long? get() = Money.parseYuanToCentsOrNull(priceYuan)

    fun validate(): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        if (title.trim().isEmpty()) errors["title"] = "请填写订单名称"
        if (title.trim().length > MAX_TITLE_LENGTH) errors["title"] = "订单名称最多 $MAX_TITLE_LENGTH 个字符"
        if (priceYuan.isBlank()) {
            errors["price"] = "请填写约定总价"
        } else if (priceCentsOrNull == null) {
            errors["price"] = "金额需为不超过两位小数的非负数字"
        }
        if (notes.trim().length > MAX_NOTES_LENGTH) errors["notes"] = "备注最多 $MAX_NOTES_LENGTH 个字符"
        if (tasks.isEmpty()) errors["tasks"] = "请至少添加一项任务"
        if (tasks.size > MAX_TASKS) errors["tasks"] = "最多 $MAX_TASKS 项任务"
        tasks.forEachIndexed { index, task ->
            task.validate(index, errors)
        }
        initialPayment?.validate(priceCentsOrNull, paymentMode, "约定总价", errors)
        return errors
    }

    companion object {
        const val MAX_TITLE_LENGTH = 40
        const val MAX_NOTES_LENGTH = 500
        const val MAX_TASKS = 30
    }
}

data class HostingOrderDraft(
    val ownerId: String,
    val accountId: String,
    val title: String = "日常托管",
    val startDate: LocalDate,
    val endDate: LocalDate,
    val priceYuan: String = "",
    val paymentMode: PaymentMode = PaymentMode.ONE_TIME,
    val notes: String = "",
    val refresh: GameRefreshRules = GameRefreshRules.provisional(),
    val tasks: List<TaskDraft> = listOf(TaskDraft()),
    val initialPayment: InitialPaymentDraft? = null,
    /** Template the creation defaults came from; see [ShortOrderDraft.sourceTemplate]. */
    val sourceTemplate: OrderTemplateProvenance? = null,
    /** Stable identity of the whole create command; see [ShortOrderDraft]. */
    val creationOperationId: String = UUID.randomUUID().toString(),
) {
    val priceCentsOrNull: Long? get() = Money.parseYuanToCentsOrNull(priceYuan)

    val durationDays: Int
        get() = GameCalendar.inclusiveDurationDays(startDate, endDate)

    fun validate(): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        if (title.trim().isEmpty()) errors["title"] = "请填写订单名称"
        if (title.trim().length > ShortOrderDraft.MAX_TITLE_LENGTH) {
            errors["title"] = "订单名称最多 ${ShortOrderDraft.MAX_TITLE_LENGTH} 个字符"
        }
        if (endDate.isBefore(startDate)) {
            errors["dates"] = "到期日期不能早于开始日期"
        } else if (durationDays > MAX_DURATION_DAYS) {
            errors["dates"] = "托管天数最多 $MAX_DURATION_DAYS 天"
        }
        if (priceYuan.isBlank()) {
            errors["price"] = "请填写本期约定价格"
        } else if (priceCentsOrNull == null) {
            errors["price"] = "金额需为不超过两位小数的非负数字"
        }
        if (notes.trim().length > ShortOrderDraft.MAX_NOTES_LENGTH) {
            errors["notes"] = "备注最多 ${ShortOrderDraft.MAX_NOTES_LENGTH} 个字符"
        }
        runCatching { ZoneId.of(refresh.zoneId.id) }.onFailure {
            errors["zone"] = "时区格式不正确，例如 Asia/Shanghai"
        }
        if (tasks.isEmpty()) errors["tasks"] = "请至少添加一项任务"
        if (tasks.size > ShortOrderDraft.MAX_TASKS) errors["tasks"] = "最多 ${ShortOrderDraft.MAX_TASKS} 项任务"
        tasks.forEachIndexed { index, task ->
            task.validate(index, errors)
            if (task.recurrence == RecurrenceType.ONE_TIME) {
                // A one-off task has no game-day occurrence, so it cannot live in a
                // hosting period. Rejecting it here is what stops a one-time template
                // task from silently becoming a daily check-in.
                errors["task.$index.recurrence"] = "一次性任务只能用于短期单；托管任务请选择周期"
            }
        }
        val weeklyTasks = tasks.filter { it.recurrence == RecurrenceType.WEEKLY }
        if (weeklyTasks.isNotEmpty()) {
            // Weekly tasks use the order refresh snapshot; no extra field is required.
        }
        initialPayment?.validate(priceCentsOrNull, paymentMode, "本期约定价格", errors)
        return errors
    }

    companion object {
        const val MAX_DURATION_DAYS = 3650
    }
}

private fun TaskDraft.validate(index: Int, errors: MutableMap<String, String>) {
    val prefix = "task.$index"
    val trimmed = title.trim()
    if (trimmed.isEmpty()) errors[prefix] = "请填写任务名称"
    if (trimmed.length > MAX_TASK_TITLE_LENGTH) errors[prefix] = "任务名称最多 $MAX_TASK_TITLE_LENGTH 个字符"
    if (progressType == ProgressType.NUMERIC) {
        val start = startDecimal()
        val target = targetDecimal()
        if (start == null) errors["$prefix.start"] = "起始值格式不正确"
        if (target == null) errors["$prefix.target"] = "目标值格式不正确"
        if (start != null && target != null && target <= start) {
            errors["$prefix.target"] = "目标值需大于起始值"
        }
    }
    when (recurrence) {
        RecurrenceType.MONTHLY -> if (monthDayOrNull() !in 1..31) {
            errors["$prefix.monthDay"] = "每月日号需为 1–31 的整数"
        }
        RecurrenceType.ONE_TIME, RecurrenceType.DAILY, RecurrenceType.WEEKLY -> Unit
        RecurrenceType.SELECTED_WEEKDAYS -> if (weekdays.isEmpty()) {
            errors["$prefix.weekdays"] = "请为指定星期任务选择执行日"
        }
        RecurrenceType.EVERY_N_DAYS -> {
            val n = everyNOrNull()
            if (n == null || n < 1 || n > 365) errors["$prefix.interval"] = "间隔天数需为 1–365 的整数"
        }
    }
}

const val MAX_TASK_TITLE_LENGTH = 40

/**
 * Shared validation so the form and the repository reject the same amounts. The
 * repository re-checks inside the transaction; this only makes the error visible early.
 */
private fun InitialPaymentDraft.validate(
    priceCents: Long?,
    paymentMode: PaymentMode,
    priceLabel: String,
    errors: MutableMap<String, String>,
) {
    if (operationId.isBlank()) {
        errors["initialPayment"] = "操作标识不能为空"
        return
    }
    if (note.trim().length > RECEIPT_NOTE_MAX_LENGTH) {
        errors["initialPayment"] = "首笔收款备注最多 $RECEIPT_NOTE_MAX_LENGTH 个字符"
        return
    }
    val amount = if (amountYuan.isBlank()) {
        errors["initialPayment"] = "请填写首笔实收金额，或关闭首笔实收"
        return
    } else {
        Money.parsePositiveYuanToCentsOrNull(amountYuan)
    }
    if (amount == null) {
        errors["initialPayment"] = "首笔实收需为大于 0 且最多两位小数的金额"
        return
    }
    if (priceCents == null) return
    if (amount > priceCents) {
        errors["initialPayment"] = "首笔实收不能超过$priceLabel ${Money.formatCents(priceCents)}"
        return
    }
    if (paymentMode == PaymentMode.ONE_TIME && amount != priceCents) {
        errors["initialPayment"] =
            "一次结清需在建单时收齐$priceLabel ${Money.formatCents(priceCents)}"
    }
}

fun TaskDraft.normalizedValues(): Triple<BigDecimal, BigDecimal, BigDecimal> {
    val start = startDecimal() ?: BigDecimal.ZERO
    val target = targetDecimal() ?: BigDecimal.ONE
    val current = start
    return Triple(start, target, current)
}
