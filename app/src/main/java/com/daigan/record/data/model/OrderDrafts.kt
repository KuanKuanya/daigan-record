package com.daigan.record.data.model

import com.daigan.record.core.time.GameCalendar
import com.daigan.record.core.time.GameRefreshRules
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

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
) {
    fun startDecimal(): BigDecimal? = parseDecimal(startValue)
    fun targetDecimal(): BigDecimal? = parseDecimal(targetValue)
    fun everyNOrNull(): Int? = everyNDays.trim().toIntOrNull()

    companion object {
        fun parseDecimal(raw: String): BigDecimal? =
            runCatching { BigDecimal(raw.trim()) }.getOrNull()
    }
}

data class ShortOrderDraft(
    val ownerId: String,
    val accountId: String,
    val title: String = "短期任务",
    val priceYuan: String = "",
    val paymentMode: PaymentMode = PaymentMode.ONE_TIME,
    val deadlineDate: LocalDate? = null,
    val notes: String = "",
    val tasks: List<TaskDraft> = listOf(TaskDraft()),
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
        }
        val weeklyTasks = tasks.filter { it.recurrence == RecurrenceType.WEEKLY }
        if (weeklyTasks.isNotEmpty()) {
            // Weekly tasks use the order refresh snapshot; no extra field is required.
        }
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
        RecurrenceType.DAILY, RecurrenceType.WEEKLY -> Unit
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

fun TaskDraft.normalizedValues(): Triple<BigDecimal, BigDecimal, BigDecimal> {
    val start = startDecimal() ?: BigDecimal.ZERO
    val target = targetDecimal() ?: BigDecimal.ONE
    val current = start
    return Triple(start, target, current)
}
