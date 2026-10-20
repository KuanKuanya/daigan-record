package com.kuankuan.daigan.data.template

import com.kuankuan.daigan.data.model.GameTemplate
import com.kuankuan.daigan.data.model.ProgressType
import com.kuankuan.daigan.data.model.RecurrenceType
import com.kuankuan.daigan.data.model.TemplateCategoryDraft
import com.kuankuan.daigan.data.model.TemplateDraft
import com.kuankuan.daigan.data.model.TemplateTaskDraft
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Outcome of reading one template file. Failure never mutates the library. */
sealed interface TemplateFileResult {
    data class Success(val content: TemplateFileContent) : TemplateFileResult

    data class Failure(val message: String) : TemplateFileResult
}

/**
 * Validated template content read from a file.
 *
 * [draft] keeps the ids written in the file so a built-in seed can preserve its stable
 * identity. An interactive import re-allocates ids before writing (see the repository)
 * so a file can never overwrite an unrelated row.
 */
data class TemplateFileContent(
    val templateId: String,
    val draft: TemplateDraft,
    val categoryCount: Int,
    val taskCount: Int,
)

/**
 * UTF-8 JSON template exchange.
 *
 * The file contains **only** template content and format metadata. Owners, accounts,
 * credentials, orders, receipts and every other business table are never written, and a
 * file that carries unknown top-level or template fields is rejected rather than
 * silently merged.
 *
 * Compatibility policy: exactly one format version (1) is accepted. A file with any
 * other `formatVersion` or `format` is rejected with a clear message, so a future format
 * can never be misread by this build. Every collection, string and numeric limit is
 * checked here, before the repository is asked to write anything.
 */
object TemplateFileCodec {

    const val FORMAT_NAME = "daigan-template"
    const val FORMAT_VERSION = 1

    /** Hard byte limit for a template file; also enforced while reading the stream. */
    const val MAX_FILE_BYTES = 256 * 1024

    private val json = Json {
        prettyPrint = true
        isLenient = false
        ignoreUnknownKeys = false
    }

    private val templateKeys = setOf(
        "id",
        "gameName",
        "templateVersion",
        "gameVersionNotes",
        "timeZone",
        "dailyRefresh",
        "weeklyRefreshDay",
        "weeklyRefreshTime",
        "categories",
    )
    private val categoryKeys = setOf("id", "name", "tasks")
    private val taskKeys = setOf(
        "id",
        "categoryId",
        "title",
        "progressType",
        "startValue",
        "targetValue",
        "unit",
        "recurrence",
        "weekdays",
        "everyNDays",
        "monthDay",
    )
    private val rootKeys = setOf("format", "formatVersion", "template")

    fun encode(template: GameTemplate): ByteArray {
        val root = buildJsonObject {
            put("format", FORMAT_NAME)
            put("formatVersion", FORMAT_VERSION)
            putJsonObject("template") {
                put("id", template.id)
                put("gameName", template.gameName)
                put("templateVersion", template.templateVersion)
                put("gameVersionNotes", template.gameVersionNotes)
                put("timeZone", template.refresh.zoneId.id)
                put("dailyRefresh", minutesToText(template.refresh.dailyRefreshMinutes))
                put("weeklyRefreshDay", template.refresh.weeklyRefreshDay.name)
                put("weeklyRefreshTime", minutesToText(template.refresh.weeklyRefreshMinutes))
                putJsonArray("categories") {
                    template.categories.forEach { category ->
                        add(
                            buildJsonObject {
                                put("id", category.id)
                                put("name", category.name)
                                putJsonArray("tasks") {
                                    category.tasks.forEach { task ->
                                        add(
                                            buildJsonObject {
                                                put("id", task.id)
                                                put("categoryId", task.categoryId)
                                                put("title", task.title)
                                                put("progressType", task.progressType.name)
                                                put("startValue", task.startValue.toPlainString())
                                                put("targetValue", task.targetValue.toPlainString())
                                                put("unit", task.unit)
                                                put("recurrence", task.recurrence.name)
                                                put(
                                                    "weekdays",
                                                    buildJsonArray {
                                                        task.weekdays.sortedBy { it.value }
                                                            .forEach { add(JsonPrimitive(it.name)) }
                                                    },
                                                )
                                                put("monthDay", task.monthDay?.let(::JsonPrimitive) ?: JsonNull)
                                                put(
                                                    "everyNDays",
                                                    task.everyNDays?.let(::JsonPrimitive) ?: JsonNull,
                                                )
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), root).toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(bytes: ByteArray): TemplateFileResult {
        if (bytes.isEmpty()) return TemplateFileResult.Failure("文件为空，未导入任何内容")
        if (bytes.size > MAX_FILE_BYTES) {
            return TemplateFileResult.Failure(
                "文件超过 ${MAX_FILE_BYTES / 1024} KB 上限，未导入任何内容",
            )
        }
        val text = decodeUtf8(bytes)
            ?: return TemplateFileResult.Failure("文件不是有效的 UTF-8 文本")
        val root = runCatching { json.parseToJsonElement(text) }.getOrElse {
            return TemplateFileResult.Failure("文件不是有效的 JSON，未导入任何内容")
        }
        return try {
            TemplateFileResult.Success(parseRoot(root))
        } catch (error: TemplateFileException) {
            TemplateFileResult.Failure(error.message ?: "模板文件内容无效")
        }
    }

    private fun parseRoot(root: JsonElement): TemplateFileContent {
        val rootObject = root.objectOrThrow("模板文件根节点必须是 JSON 对象")
        rejectUnknownKeys(rootObject, rootKeys, "模板文件")
        val format = rootObject.requireString("format")
        if (format != FORMAT_NAME) {
            throw TemplateFileException("这不是本应用的模板文件（format=$format）")
        }
        val version = rootObject.requireInt("formatVersion")
        if (version != FORMAT_VERSION) {
            throw TemplateFileException(
                "不支持的模板文件格式版本 $version；本版本只支持 $FORMAT_VERSION",
            )
        }
        val template = rootObject["template"]
            ?.objectOrThrow("template 必须是对象")
            ?: throw TemplateFileException("缺少必填字段：template")
        rejectUnknownKeys(template, templateKeys, "模板")
        val templateId = template.requireId("id")
        val gameName = template.requireString("gameName")
        val templateVersion = template.requireString("templateVersion")
        val notes = template.optionalString("gameVersionNotes")
        val zone = template.requireString("timeZone")
        val dailyRefresh = parseTime(template.requireString("dailyRefresh"), "每日刷新时间")
        val weeklyDay = parseDay(template.requireString("weeklyRefreshDay"))
        val weeklyTime = parseTime(template.requireString("weeklyRefreshTime"), "每周刷新时间")

        val categoriesArray = template["categories"]
            ?.arrayOrThrow("categories 必须是数组")
            ?: throw TemplateFileException("缺少必填字段：categories")
        if (categoriesArray.isEmpty()) {
            throw TemplateFileException("模板至少需要一个分类")
        }
        if (categoriesArray.size > TemplateDraft.MAX_CATEGORIES) {
            throw TemplateFileException("分类数量超过 ${TemplateDraft.MAX_CATEGORIES} 个上限")
        }
        val seenIds = mutableSetOf<String>()
        fun uniqueId(raw: String): String {
            if (!seenIds.add(raw)) throw TemplateFileException("文件中的标识重复：$raw")
            return raw
        }
        var taskTotal = 0
        val categories = categoriesArray.mapIndexed { index, element ->
            val category = element.objectOrThrow("第 ${index + 1} 个分类必须是对象")
            rejectUnknownKeys(category, categoryKeys, "分类")
            val categoryId = uniqueId(category.requireId("id"))
            val name = category.requireString("name")
            val tasksArray = category["tasks"]
                ?.arrayOrThrow("tasks 必须是数组")
                ?: throw TemplateFileException("分类「$name」缺少必填字段：tasks")
            if (tasksArray.isEmpty()) {
                throw TemplateFileException("分类「$name」至少需要一个任务选项")
            }
            if (tasksArray.size > TemplateDraft.MAX_TASKS) {
                throw TemplateFileException("单个分类的任务数量超过 ${TemplateDraft.MAX_TASKS} 个上限")
            }
            taskTotal += tasksArray.size
            if (taskTotal > TemplateDraft.MAX_TASKS) {
                throw TemplateFileException("任务总数超过 ${TemplateDraft.MAX_TASKS} 个上限")
            }
            val tasks = tasksArray.mapIndexed { taskIndex, taskElement ->
                val task = taskElement.objectOrThrow("第 ${taskIndex + 1} 个任务必须是对象")
                rejectUnknownKeys(task, taskKeys, "任务")
                val taskId = uniqueId(task.requireId("id"))
                val referencedCategory = task.requireString("categoryId")
                if (referencedCategory != categoryId) {
                    throw TemplateFileException(
                        "任务「${task.optionalString("title").orEmpty()}」的 categoryId 与所在分类不一致",
                    )
                }
                TemplateTaskDraft(
                    id = taskId,
                    title = task.requireString("title"),
                    progressType = parseProgressType(task.requireString("progressType")),
                    startValue = task.requireDecimalText("startValue"),
                    targetValue = task.requireDecimalText("targetValue"),
                    unit = task.optionalString("unit").orEmpty(),
                    recurrence = parseRecurrence(task.requireString("recurrence")),
                    weekdays = parseWeekdays(task),
                    everyNDays = task.optionalInt("everyNDays")?.toString().orEmpty(),
                    monthDay = task.optionalInt("monthDay")?.toString().orEmpty(),
                )
            }
            TemplateCategoryDraft(id = categoryId, name = name, tasks = tasks)
        }

        val draft = TemplateDraft(
            gameName = gameName,
            templateVersion = templateVersion,
            gameVersionNotes = notes.orEmpty(),
            zoneText = zone,
            dailyRefresh = dailyRefresh,
            weeklyRefreshDay = weeklyDay,
            weeklyRefreshTime = weeklyTime,
            categories = categories,
        )
        val errors = draft.validate()
        if (errors.isNotEmpty()) {
            throw TemplateFileException(errors.values.first())
        }
        return TemplateFileContent(
            templateId = templateId,
            draft = draft,
            categoryCount = categories.size,
            taskCount = taskTotal,
        )
    }

    private fun parseProgressType(raw: String): ProgressType = when (raw) {
        ProgressType.CHECKBOX.name -> ProgressType.CHECKBOX
        ProgressType.NUMERIC.name -> ProgressType.NUMERIC
        else -> throw TemplateFileException("不支持的任务记录方式：$raw")
    }

    private fun parseRecurrence(raw: String): RecurrenceType = when (raw) {
        RecurrenceType.ONE_TIME.name -> RecurrenceType.ONE_TIME
        RecurrenceType.DAILY.name -> RecurrenceType.DAILY
        RecurrenceType.WEEKLY.name -> RecurrenceType.WEEKLY
        RecurrenceType.SELECTED_WEEKDAYS.name -> RecurrenceType.SELECTED_WEEKDAYS
        RecurrenceType.MONTHLY.name -> RecurrenceType.MONTHLY
        RecurrenceType.EVERY_N_DAYS.name -> RecurrenceType.EVERY_N_DAYS
        else -> throw TemplateFileException("不支持的任务重复周期：$raw")
    }

    private fun parseDay(raw: String): DayOfWeek = runCatching { DayOfWeek.valueOf(raw) }
        .getOrElse { throw TemplateFileException("每周刷新星期无效：$raw") }

    private fun parseTime(raw: String, label: String): LocalTime = try {
        LocalTime.parse(raw)
    } catch (error: DateTimeParseException) {
        throw TemplateFileException("$label 格式无效：$raw")
    }

    private fun parseWeekdays(task: JsonObject): Set<DayOfWeek> {
        val element = task["weekdays"] ?: return emptySet()
        if (element is JsonNull) return emptySet()
        val array = element.arrayOrThrow("weekdays 必须是数组")
        return array.map { day ->
            val raw = day.requireStringValue("weekdays")
            runCatching { DayOfWeek.valueOf(raw) }
                .getOrElse { throw TemplateFileException("执行星期无效：$raw") }
        }.toSet()
    }

    private fun rejectUnknownKeys(objectValue: JsonObject, allowed: Set<String>, label: String) {
        val unknown = objectValue.keys - allowed
        if (unknown.isNotEmpty()) {
            throw TemplateFileException(
                "$label 包含不支持的字段：${unknown.sorted().joinToString("、")}",
            )
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun minutesToText(minutes: Int): String =
        "%02d:%02d".format(minutes / 60, minutes % 60)
}

private class TemplateFileException(message: String) : Exception(message)

private fun JsonElement.objectOrThrow(message: String): JsonObject =
    this as? JsonObject ?: throw TemplateFileException(message)

private fun JsonElement.arrayOrThrow(message: String): JsonArray =
    this as? JsonArray ?: throw TemplateFileException(message)

private fun JsonObject.requireString(key: String): String {
    val element = this[key] ?: throw TemplateFileException("缺少必填字段：$key")
    val primitive = element as? JsonPrimitive
        ?: throw TemplateFileException("字段 $key 必须是文本")
    if (primitive is JsonNull || !primitive.isString) {
        throw TemplateFileException("字段 $key 必须是文本")
    }
    val value = primitive.content
    if (value.isBlank()) throw TemplateFileException("字段 $key 不能为空")
    if (value.length > MAX_TEXT_FIELD_LENGTH) {
        throw TemplateFileException("字段 $key 超过 $MAX_TEXT_FIELD_LENGTH 个字符上限")
    }
    return value
}

private fun JsonObject.optionalString(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive
        ?: throw TemplateFileException("字段 $key 必须是文本")
    if (!primitive.isString) throw TemplateFileException("字段 $key 必须是文本")
    if (primitive.content.length > MAX_NOTES_FIELD_LENGTH) {
        throw TemplateFileException("字段 $key 超过 $MAX_NOTES_FIELD_LENGTH 个字符上限")
    }
    return primitive.content.takeIf { it.isNotBlank() }
}

private fun JsonObject.requireInt(key: String): Int {
    val element = this[key] ?: throw TemplateFileException("缺少必填字段：$key")
    val primitive = element as? JsonPrimitive
        ?: throw TemplateFileException("字段 $key 必须是整数")
    return primitive.intOrNull ?: throw TemplateFileException("字段 $key 必须是整数")
}

private fun JsonObject.optionalInt(key: String): Int? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive
        ?: throw TemplateFileException("字段 $key 必须是整数或 null")
    return primitive.intOrNull ?: throw TemplateFileException("字段 $key 必须是整数或 null")
}

private fun JsonObject.requireDecimalText(key: String): String {
    val element = this[key] ?: throw TemplateFileException("缺少必填字段：$key")
    val primitive = element as? JsonPrimitive
        ?: throw TemplateFileException("字段 $key 必须是数值")
    if (primitive is JsonNull) throw TemplateFileException("字段 $key 必须是数值")
    val raw = primitive.content
    val value = runCatching { BigDecimal(raw) }.getOrNull()
        ?: throw TemplateFileException("字段 $key 不是有效数值")
    if (value.scale() > TemplateTaskDraft.MAX_DECIMAL_SCALE) {
        throw TemplateFileException("字段 $key 最多 ${TemplateTaskDraft.MAX_DECIMAL_SCALE} 位小数")
    }
    return raw
}

private fun String.requireId(key: String): String {
    if (isBlank()) throw TemplateFileException("字段 $key 不能为空")
    if (length > MAX_ID_LENGTH) throw TemplateFileException("字段 $key 过长")
    return this
}

private fun JsonObject.requireId(key: String): String = requireString(key).requireId(key)

private fun JsonElement.requireStringValue(label: String): String {
    val primitive = this as? JsonPrimitive
        ?: throw TemplateFileException("$label 必须是文本")
    if (primitive is JsonNull || !primitive.isString) {
        throw TemplateFileException("$label 必须是文本")
    }
    return primitive.content
}

private const val MAX_ID_LENGTH = 100
private const val MAX_TEXT_FIELD_LENGTH = 200
private const val MAX_NOTES_FIELD_LENGTH = 2000
