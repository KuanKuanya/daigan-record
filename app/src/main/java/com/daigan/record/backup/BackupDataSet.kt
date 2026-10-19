package com.daigan.record.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement as JsonElementAlias

/**
 * The complete structured payload of a backup: one entry per durable table, in parent
 * order, with values in the catalog's column order.
 *
 * Rows are arrays rather than objects on purpose. A duplicated column or table key is
 * then impossible in a generated file and detectable in a foreign one, and the parser
 * can validate every value against the declared column type before anything is written
 * to a database.
 */
class BackupDataSet(val tables: List<TableRows>) {

    data class TableRows(
        val table: BackupTableCatalog.Table,
        /** One value per catalog column: a JSON primitive, or null for SQL NULL. */
        val rows: List<List<JsonElement?>>,
    ) {
        val rowCount: Int get() = rows.size
    }

    fun rows(tableName: String): List<List<JsonElement?>> =
        tables.firstOrNull { it.table.name == tableName }?.rows ?: emptyList()

    fun count(tableName: String): Int = rows(tableName).size

    fun isEmpty(): Boolean = tables.all { it.rows.isEmpty() }

    /** Attachment metadata rows, decoded from the `attachments` table. */
    data class AttachmentRow(
        val id: String,
        val relativePath: String,
        val mimeType: String,
        val sizeBytes: Long,
    )

    fun attachmentRows(): List<AttachmentRow> {
        val spec = checkNotNull(BackupTableCatalog.table("attachments"))
        fun index(name: String) = spec.columnNames.indexOf(name)
        val idIndex = index("id")
        val pathIndex = index("relativePath")
        val mimeIndex = index("mimeType")
        val sizeIndex = index("sizeBytes")
        return rows("attachments").map { row ->
            AttachmentRow(
                id = (row[idIndex] as? JsonPrimitive)?.content
                    ?: throw BackupException("备份中的截图记录缺少编号，无法恢复。"),
                relativePath = (row[pathIndex] as? JsonPrimitive)?.content
                    ?: throw BackupException("备份中的截图记录缺少文件名，无法恢复。"),
                mimeType = (row[mimeIndex] as? JsonPrimitive)?.content.orEmpty(),
                sizeBytes = (row[sizeIndex] as? JsonPrimitive)?.content?.toLongOrNull()
                    ?: throw BackupException("备份中的截图记录大小无效，无法恢复。"),
            )
        }
    }

    companion object {
        const val DATA_FORMAT = "daigan-backup-data"
        const val DATA_FORMAT_VERSION = 1

        /** Maximum JSON nesting accepted from a file (defensive bound). */
        const val MAX_JSON_DEPTH = 32
    }
}

/**
 * Encodes and strictly decodes [BackupDataSet]. Decoding never trusts the file:
 * the format marker, version, the exact table set, the exact column list of every table
 * and every value's JSON type are all verified.
 */
object BackupDataCodec {

    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
        allowTrailingComma = false
    }

    fun encode(dataSet: BackupDataSet): String {
        val tables = JsonArray(
            dataSet.tables.map { tableRows ->
                JsonObject(
                    linkedMapOf(
                        "name" to JsonPrimitive(tableRows.table.name),
                        "columns" to JsonArray(
                            tableRows.table.columnNames.map { JsonPrimitive(it) },
                        ),
                        "rows" to JsonArray(
                            tableRows.rows.map { row ->
                                JsonArray(row.map { it ?: JsonNull })
                            },
                        ),
                    ),
                )
            },
        )
        val root = JsonObject(
            linkedMapOf(
                "format" to JsonPrimitive(BackupDataSet.DATA_FORMAT),
                "formatVersion" to JsonPrimitive(BackupDataSet.DATA_FORMAT_VERSION),
                "tables" to tables,
            ),
        )
        return json.encodeToString(JsonElementAlias.serializer(), root)
    }

    fun decode(text: String): BackupDataSet {
        JsonSafety.requireBoundedDepth(text, BackupDataSet.MAX_JSON_DEPTH)
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw BackupException("备份的数据内容已损坏，无法恢复。")
        if (root.string("format") != BackupDataSet.DATA_FORMAT) {
            throw BackupException("备份的数据格式无法识别，无法恢复。")
        }
        val formatVersion = root.int("formatVersion")
        if (formatVersion != BackupDataSet.DATA_FORMAT_VERSION) {
            throw BackupException("备份的数据格式版本（$formatVersion）不受支持，无法恢复。")
        }
        val tableArray = root["tables"] as? JsonArray
            ?: throw BackupException("备份缺少数据表清单，无法恢复。")

        val expected = BackupTableCatalog.includedTables
        val byName = LinkedHashMap<String, BackupDataSet.TableRows>()
        tableArray.forEach { element ->
            val tableObject = element as? JsonObject
                ?: throw BackupException("备份的数据表清单格式无效，无法恢复。")
            val name = tableObject.string("name")
                ?: throw BackupException("备份的数据表缺少名称，无法恢复。")
            if (byName.containsKey(name)) {
                throw BackupException("备份中重复出现数据表「$name」，无法恢复。")
            }
            val spec = expected.firstOrNull { it.name == name }
                ?: throw BackupException("备份包含不受支持的数据表「$name」，无法恢复。")
            val columnsElement = tableObject["columns"] as? JsonArray
                ?: throw BackupException("备份的数据表「$name」缺少列说明，无法恢复。")
            val columns = columnsElement.map { (it as? JsonPrimitive)?.content }
            val addedColumn = when (name) {
                "orders" -> "cancelledAt"
                "task_definitions", "template_tasks" -> "monthDay"
                else -> null
            }
            val legacyColumns = addedColumn != null && columns == spec.columnNames.filter { it != addedColumn }
            if (columns != spec.columnNames && !legacyColumns) {
                throw BackupException("备份的数据表「$name」列不符合当前版本，无法恢复。")
            }
            val rowsElement = tableObject["rows"] as? JsonArray
                ?: throw BackupException("备份的数据表「$name」缺少记录，无法恢复。")
            if (spec.singleton && rowsElement.size > 1) {
                throw BackupException("备份中的单例设置重复，无法恢复。")
            }
            val rows = rowsElement.map { rowElement ->
                val rowArray = rowElement as? JsonArray
                    ?: throw BackupException("备份的数据表「$name」记录格式无效，无法恢复。")
                if (rowArray.size != columns.size) {
                    throw BackupException("备份的数据表「$name」记录列数不正确，无法恢复。")
                }
                val row = spec.columns.map { column ->
                    val sourceIndex = columns.indexOf(column.name)
                    if (sourceIndex < 0) null else decodeValue(column, rowArray[sourceIndex], spec.name)
                }
                if (spec.singleton && (row[spec.columnNames.indexOf("id")] as? JsonPrimitive)?.content != "0") {
                    throw BackupException("备份中的单例设置编号无效，无法恢复。")
                }
                row
            }
            byName[name] = BackupDataSet.TableRows(spec, rows)
        }
        val missing = expected.filter { it.name !in byName }
        if (missing.isNotEmpty()) {
            throw BackupException(
                "备份缺少数据表「${missing.first().name}」，无法恢复。",
            )
        }
        return BackupDataSet(expected.map { byName.getValue(it.name) })
    }

    private fun decodeValue(
        column: BackupTableCatalog.Column,
        value: JsonElement,
        table: String,
    ): JsonElement? {
        if (value is JsonNull) return null
        val primitive = value as? JsonPrimitive
            ?: throw BackupException("备份的数据表「$table」包含无效值，无法恢复。")
        return when (column.type) {
            BackupTableCatalog.ColumnType.TEXT -> {
                if (!primitive.isString) {
                    throw BackupException("备份的数据表「$table」文本字段类型不正确，无法恢复。")
                }
                primitive
            }

            BackupTableCatalog.ColumnType.INTEGER -> {
                if (primitive.isString) {
                    throw BackupException("备份的数据表「$table」数字字段类型不正确，无法恢复。")
                }
                val raw = primitive.content
                val booleanColumn = (table == "reminder_settings" && column.name.endsWith("Enabled")) ||
                    (table == "task_occurrences" && column.name == "backfilled") ||
                    (table == "completion_batch_items" && column.name in setOf("previousBackfilled", "appliedBackfilled"))
                if (booleanColumn && raw !in setOf("0", "1")) {
                    throw BackupException("备份中的开关字段无效，无法恢复。")
                }
                val intColumn = column.name in setOf("number", "nextNumber", "periodIndex",
                    "dailyRefreshMinutes", "weeklyRefreshDay", "weeklyRefreshMinutes", "sortOrder",
                    "everyNDays", "monthDay", "shortDeadlineMinutes", "hostingExpiryMinutes", "unfinishedTasksMinutes")
                if (intColumn && raw.toIntOrNull() == null) {
                    throw BackupException("备份中的整数字段超出支持范围，无法恢复。")
                }
                if (raw.toLongOrNull() == null) {
                    throw BackupException("备份的数据表「$table」数字字段超出范围，无法恢复。")
                }
                primitive
            }
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
}

/**
 * Two cheap structural guards used before a JSON parser or a ZIP reader sees a file.
 * Both operate in one pass and never allocate on the input's behalf.
 */
object JsonSafety {

    /**
     * Rejects documents nested deeper than [maxDepth]. A bounded depth keeps a hostile
     * (but size-legal) document from exhausting the parser stack. Brackets inside string
     * literals are ignored.
     */
    fun requireBoundedDepth(text: String, maxDepth: Int) {
        var depth = 0
        var inString = false
        var escaped = false
        for (character in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                continue
            }
            when (character) {
                '"' -> inString = true
                '{', '[' -> {
                    depth += 1
                    if (depth > maxDepth) {
                        throw BackupException("备份的数据结构过于复杂，无法恢复。")
                    }
                }
                '}', ']' -> depth -= 1
                else -> Unit
            }
        }
    }
}
