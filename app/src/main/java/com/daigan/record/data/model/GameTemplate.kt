package com.daigan.record.data.model

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Where a stored template came from. The label is shown in the library, never inferred. */
enum class TemplateOrigin { BUILT_IN, USER, IMPORTED }

/**
 * Template recurrence, expressed on the **template task** itself.
 *
 * `ONE_TIME` is the explicit representation of short-order applicability: the task is
 * performed once and has no game-day or game-week occurrence. A hosting order rejects
 * it (see [HostingOrderDraft.validate]) instead of treating it as `DAILY`; a short
 * order accepts it and never generates occurrences for any recurrence.
 */
typealias TemplateRecurrence = RecurrenceType

/** Which kind of order a template's tasks can supply defaults for. */
enum class TemplateOrderKind { HOSTING, SHORT_TASK }

data class TemplateCategory(
    val id: String,
    val templateId: String,
    val name: String,
    val sortOrder: Int,
    val tasks: List<TemplateTask>,
)

data class TemplateTask(
    val id: String,
    val templateId: String,
    val categoryId: String,
    val title: String,
    val progressType: ProgressType,
    val startValue: BigDecimal,
    val targetValue: BigDecimal,
    val unit: String?,
    val recurrence: TemplateRecurrence,
    val weekdays: Set<DayOfWeek>,
    val everyNDays: Int?,
    val sortOrder: Int,
) {
    val oneTime: Boolean get() = recurrence == RecurrenceType.ONE_TIME
}

/**
 * A persisted, editable template. Categories and tasks are ordered snapshots owned by
 * the template; no order ever references these rows, so editing or deleting a template
 * cannot rewrite an existing order.
 */
data class GameTemplate(
    val id: String,
    val gameName: String,
    val templateVersion: String,
    val gameVersionNotes: String?,
    val refresh: GameRefreshRulesDomain,
    val origin: TemplateOrigin,
    /** Stable seed identity for a built-in template; null for user/imported rows. */
    val builtInKey: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val categories: List<TemplateCategory>,
    /** Stable library display order; built-ins are 0, 1, 2 in preparation order. */
    val sortOrder: Int = 0,
) {
    val tasks: List<TemplateTask> get() = categories.flatMap { it.tasks }

    fun tasksFor(kind: TemplateOrderKind): List<TemplateTask> = when (kind) {
        TemplateOrderKind.HOSTING -> tasks.filter { !it.oneTime }
        TemplateOrderKind.SHORT_TASK -> tasks.filter { it.oneTime }
    }

    fun supports(kind: TemplateOrderKind): Boolean = tasksFor(kind).isNotEmpty()
}

/** Lightweight row for pickers that must not carry every task into a list item. */
data class GameTemplateSummary(
    val id: String,
    val gameName: String,
    val templateVersion: String,
    val gameVersionNotes: String?,
    val refresh: GameRefreshRulesDomain,
    val origin: TemplateOrigin,
    val categoryCount: Int,
    val taskCount: Int,
    val hostingTaskCount: Int,
    val shortTaskCount: Int,
    val sortOrder: Int = 0,
) {
    val label: String get() = "$gameName · $templateVersion"

    fun supports(kind: TemplateOrderKind): Boolean = when (kind) {
        TemplateOrderKind.HOSTING -> hostingTaskCount > 0
        TemplateOrderKind.SHORT_TASK -> shortTaskCount > 0
    }
}

/**
 * Editable template content. Ids are stable while the editor is open; the repository
 * re-allocates ids that would collide with another template and never reuses an id from
 * a different template.
 */
data class TemplateDraft(
    val gameName: String = "",
    val templateVersion: String = "1.0",
    val gameVersionNotes: String = "",
    val zoneText: String = "Asia/Shanghai",
    val dailyRefresh: LocalTime = LocalTime.of(4, 0),
    val weeklyRefreshDay: DayOfWeek = DayOfWeek.MONDAY,
    val weeklyRefreshTime: LocalTime = LocalTime.of(5, 0),
    val categories: List<TemplateCategoryDraft> = listOf(TemplateCategoryDraft()),
) {
    val taskCount: Int get() = categories.sumOf { it.tasks.size }
    val hostingTaskCount: Int
        get() = categories.sumOf { category -> category.tasks.count { it.recurrence != RecurrenceType.ONE_TIME } }
    val shortTaskCount: Int
        get() = categories.sumOf { category -> category.tasks.count { it.recurrence == RecurrenceType.ONE_TIME } }

    fun zoneOrNull(): ZoneId? = runCatching { ZoneId.of(zoneText.trim()) }.getOrNull()

    /** Trimmed text and normalized numbers; validation runs on this copy. */
    fun normalized(): TemplateDraft = copy(
        gameName = gameName.trim(),
        templateVersion = templateVersion.trim(),
        gameVersionNotes = gameVersionNotes.trim(),
        zoneText = zoneText.trim(),
        categories = categories.map { it.normalized() },
    )

    /**
     * Returns Chinese per-field messages keyed by stable field keys. Empty means the
     * draft can be saved. The same rules guard the JSON codec and the repository.
     */
    fun validate(): Map<String, String> {
        val draft = normalized()
        val errors = linkedMapOf<String, String>()
        if (draft.gameName.isEmpty()) errors["gameName"] = "请填写游戏名称"
        if (draft.gameName.length > MAX_GAME_NAME_LENGTH) {
            errors["gameName"] = "游戏名称最多 $MAX_GAME_NAME_LENGTH 个字符"
        }
        if (draft.templateVersion.isEmpty()) errors["templateVersion"] = "请填写模板版本"
        if (draft.templateVersion.length > MAX_VERSION_LENGTH) {
            errors["templateVersion"] = "模板版本最多 $MAX_VERSION_LENGTH 个字符"
        }
        if (draft.gameVersionNotes.length > MAX_NOTES_LENGTH) {
            errors["gameVersionNotes"] = "游戏版本备注最多 $MAX_NOTES_LENGTH 个字符"
        }
        if (draft.zoneOrNull() == null) errors["zone"] = "时区格式不正确，例如 Asia/Shanghai"
        if (draft.categories.isEmpty()) errors["categories"] = "请至少保留一个分类"
        if (draft.categories.size > MAX_CATEGORIES) errors["categories"] = "最多 $MAX_CATEGORIES 个分类"
        if (draft.taskCount == 0) errors["tasks"] = "请至少保留一个任务选项"
        if (draft.taskCount > MAX_TASKS) errors["tasks"] = "最多 $MAX_TASKS 个任务选项"
        draft.categories.forEachIndexed { index, category ->
            if (category.name.isEmpty()) errors["category.$index"] = "请填写分类名称"
            if (category.name.length > MAX_CATEGORY_NAME_LENGTH) {
                errors["category.$index"] = "分类名称最多 $MAX_CATEGORY_NAME_LENGTH 个字符"
            }
            if (category.tasks.isEmpty()) {
                errors["category.$index.tasks"] = "每个分类至少需要一个任务选项"
            }
            category.tasks.forEachIndexed { taskIndex, task ->
                task.validate("category.$index.task.$taskIndex", errors)
            }
        }
        return errors
    }

    fun withNewIds(): TemplateDraft = copy(
        categories = categories.map { category ->
            category.copy(
                id = UUID.randomUUID().toString(),
                tasks = category.tasks.map { it.copy(id = UUID.randomUUID().toString()) },
            )
        },
    )

    companion object {
        const val MAX_GAME_NAME_LENGTH = 40
        const val MAX_VERSION_LENGTH = 30
        const val MAX_NOTES_LENGTH = 300
        const val MAX_CATEGORIES = 20
        const val MAX_TASKS = 100
        const val MAX_CATEGORY_NAME_LENGTH = 30
        const val MAX_TASK_TITLE_LENGTH = 40
        const val MAX_UNIT_LENGTH = 10

        fun from(template: GameTemplate): TemplateDraft = TemplateDraft(
            gameName = template.gameName,
            templateVersion = template.templateVersion,
            gameVersionNotes = template.gameVersionNotes.orEmpty(),
            zoneText = template.refresh.zoneId.id,
            dailyRefresh = template.refresh.dailyRefresh,
            weeklyRefreshDay = template.refresh.weeklyRefreshDay,
            weeklyRefreshTime = template.refresh.weeklyRefreshTime,
            categories = template.categories.map { category ->
                TemplateCategoryDraft(
                    id = category.id,
                    name = category.name,
                    tasks = category.tasks.map { task ->
                        TemplateTaskDraft(
                            id = task.id,
                            title = task.title,
                            progressType = task.progressType,
                            startValue = task.startValue.toPlainString(),
                            targetValue = task.targetValue.toPlainString(),
                            unit = task.unit.orEmpty(),
                            recurrence = task.recurrence,
                            weekdays = task.weekdays,
                            everyNDays = task.everyNDays?.toString().orEmpty(),
                        )
                    },
                )
            },
        )
    }
}

data class TemplateCategoryDraft(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val tasks: List<TemplateTaskDraft> = listOf(TemplateTaskDraft()),
) {
    fun normalized(): TemplateCategoryDraft = copy(
        name = name.trim(),
        tasks = tasks.map { it.normalized() },
    )
}

data class TemplateTaskDraft(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val progressType: ProgressType = ProgressType.CHECKBOX,
    val startValue: String = "0",
    val targetValue: String = "1",
    val unit: String = "",
    /** Default for a template task is a daily check-in, like the order form. */
    val recurrence: RecurrenceType = RecurrenceType.DAILY,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val everyNDays: String = "",
) {
    fun startDecimal(): BigDecimal? = parseDecimal(startValue)
    fun targetDecimal(): BigDecimal? = parseDecimal(targetValue)
    fun everyNOrNull(): Int? = everyNDays.trim().toIntOrNull()

    fun normalized(): TemplateTaskDraft = copy(
        title = title.trim(),
        unit = if (progressType == ProgressType.NUMERIC) unit.trim() else "",
        startValue = if (progressType == ProgressType.NUMERIC) startValue.trim() else "0",
        targetValue = if (progressType == ProgressType.NUMERIC) targetValue.trim() else "1",
        everyNDays = if (recurrence == RecurrenceType.EVERY_N_DAYS) everyNDays.trim() else "",
        weekdays = if (recurrence == RecurrenceType.SELECTED_WEEKDAYS) weekdays else emptySet(),
    )

    internal fun validate(key: String, errors: MutableMap<String, String>) {
        val task = normalized()
        if (task.title.isEmpty()) errors[key] = "请填写任务名称"
        if (task.title.length > TemplateDraft.MAX_TASK_TITLE_LENGTH) {
            errors[key] = "任务名称最多 ${TemplateDraft.MAX_TASK_TITLE_LENGTH} 个字符"
        }
        if (task.unit.length > TemplateDraft.MAX_UNIT_LENGTH) {
            errors["$key.unit"] = "单位最多 ${TemplateDraft.MAX_UNIT_LENGTH} 个字符"
        }
        if (task.progressType == ProgressType.NUMERIC) {
            val start = task.startDecimal()
            val target = task.targetDecimal()
            if (start == null) errors["$key.start"] = "起始值格式不正确"
            if (target == null) errors["$key.target"] = "目标值格式不正确"
            if (start != null && start < BigDecimal.ZERO) errors["$key.start"] = "起始值不能为负数"
            if (start != null && start.scale() > MAX_DECIMAL_SCALE) {
                errors["$key.start"] = "起始值最多 $MAX_DECIMAL_SCALE 位小数"
            }
            if (target != null && target.scale() > MAX_DECIMAL_SCALE) {
                errors["$key.target"] = "目标值最多 $MAX_DECIMAL_SCALE 位小数"
            }
            if (start != null && target != null && target <= start) {
                errors["$key.target"] = "目标值需大于起始值"
            }
            if (target != null && target.abs() > MAX_DECIMAL_ABS) {
                errors["$key.target"] = "目标值过大"
            }
        }
        when (task.recurrence) {
            RecurrenceType.SELECTED_WEEKDAYS -> if (task.weekdays.isEmpty()) {
                errors["$key.weekdays"] = "请为指定星期任务选择执行日"
            }
            RecurrenceType.EVERY_N_DAYS -> {
                val n = task.everyNOrNull()
                if (n == null || n < 1 || n > MAX_INTERVAL_DAYS) {
                    errors["$key.interval"] = "间隔天数需为 1–$MAX_INTERVAL_DAYS 的整数"
                }
            }
            RecurrenceType.ONE_TIME, RecurrenceType.DAILY, RecurrenceType.WEEKLY -> Unit
        }
    }

    companion object {
        const val MAX_DECIMAL_SCALE = 3
        const val MAX_INTERVAL_DAYS = 365
        val MAX_DECIMAL_ABS: BigDecimal = BigDecimal("1000000000000")

        fun parseDecimal(raw: String): BigDecimal? =
            runCatching { BigDecimal(raw.trim()) }.getOrNull()
    }
}

/** Why a template cannot supply defaults for the chosen order type. */
const val TEMPLATE_NO_HOSTING_TASKS_MESSAGE =
    "该模板没有可用于托管的任务（一次性任务只能用于短期单）"
const val TEMPLATE_NO_SHORT_TASKS_MESSAGE =
    "该模板没有可用于短期单的一次性任务"

/**
 * Converts a stored template task into the order-creation form representation.
 * The template's start/target defaults become the order task's initial values, which
 * the user can still edit before creating the order.
 */
fun TemplateTask.toTaskDraft(): TaskDraft = TaskDraft(
    title = title,
    progressType = progressType,
    startValue = startValue.toPlainString(),
    targetValue = targetValue.toPlainString(),
    unit = unit.orEmpty(),
    recurrence = recurrence,
    weekdays = weekdays,
    everyNDays = everyNDays?.toString().orEmpty(),
)

fun GameTemplate.provenance(): OrderTemplateProvenance = OrderTemplateProvenance(
    templateId = id,
    gameName = gameName,
    templateVersion = templateVersion,
)
