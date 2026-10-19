package com.daigan.record.backup

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class BackupV16CompatibilityTest {
    private val added = mapOf("orders" to "cancelledAt", "task_definitions" to "monthDay", "template_tasks" to "monthDay")
    private val legacyRows: Map<String, Map<String, Any?>> = mapOf(
        "orders" to linkedMapOf(
            "id" to "order-old", "accountId" to "account-old", "type" to "SHORT_TASK",
            "title" to "旧订单", "priceCents" to 12345L, "paymentMode" to "INSTALLMENTS",
            "deadlineDate" to "2026-10-30", "notes" to "旧备注", "gameZoneId" to "Asia/Shanghai",
            "dailyRefreshMinutes" to 240L, "weeklyRefreshDay" to 2L, "weeklyRefreshMinutes" to 300L,
            "createdAt" to 1700000000000L, "updatedAt" to 1700000001000L, "completedAt" to null,
            "sourceTemplateId" to "template-old", "sourceTemplateName" to "旧模板", "sourceTemplateVersion" to "v7",
        ),
        "task_definitions" to linkedMapOf(
            "id" to "task-old", "orderId" to "order-old", "periodId" to null,
            "title" to "旧任务", "progressType" to "NUMBER", "startValue" to "2.5",
            "targetValue" to "99.5", "currentValue" to "5.5", "unit" to "个",
            "recurrence" to "EVERY_N_DAYS", "weekdays" to null, "everyNDays" to 3L,
            "sortOrder" to 7L, "completedAt" to null, "completionBatchId" to null,
            "createdAt" to 1700000002000L, "updatedAt" to 1700000003000L,
        ),
        "template_tasks" to linkedMapOf(
            "id" to "template-task-old", "templateId" to "template-old", "categoryId" to "category-old",
            "title" to "旧模板任务", "progressType" to "NUMBER", "startValue" to "10.5",
            "targetValue" to "50.5", "unit" to "份", "recurrence" to "EVERY_N_DAYS",
            "weekdays" to null, "everyNDays" to 4L, "sortOrder" to 8L,
        ),
    )

    private fun value(raw: Any?): JsonElement? = when (raw) {
        null -> null
        is Long -> JsonPrimitive(raw)
        else -> JsonPrimitive(raw as String)
    }

    private fun legacyData(): BackupDataSet = BackupDataSet(BackupTableCatalog.includedTables.map { table ->
        val legacyTable = table.copy(columns = table.columns.filter { it.name != added[table.name] })
        val data = legacyRows[table.name]
        BackupDataSet.TableRows(legacyTable, if (data == null) emptyList() else
            listOf(legacyTable.columns.map { column -> value(data.getValue(column.name)) }))
    })

    @Test
    fun versionSevenBackupAddsNullFieldsWithoutShiftingAnyLegacyValue() {
        val decoded = BackupDataCodec.decode(BackupDataCodec.encode(legacyData()))
        assertEquals(BackupTableCatalog.includedTables.size, decoded.tables.size)
        for ((name, expected) in legacyRows) {
            val table = decoded.tables.single { it.table.name == name }
            val row = table.rows.single()
            for ((column, original) in expected) {
                assertEquals("$name.$column", value(original), row[table.table.columnNames.indexOf(column)])
            }
            assertNull("$name new field", row[table.table.columnNames.indexOf(added.getValue(name))])
        }
        assertEquals(3, decoded.tables.sumOf { it.rows.size })
    }

    @Test
    fun missingAnyOriginalColumnIsRejectedEvenWhenNewColumnsAreOptional() {
        val legacy = legacyData()
        for (target in legacy.tables) for (columnIndex in target.table.columns.indices) {
            val broken = BackupDataSet(legacy.tables.map { table ->
                if (table.table.name != target.table.name) table else BackupDataSet.TableRows(
                    table.table.copy(columns = table.table.columns.filterIndexed { index, _ -> index != columnIndex }),
                    table.rows.map { row -> row.filterIndexed { index, _ -> index != columnIndex } },
                )
            })
            try {
                BackupDataCodec.decode(BackupDataCodec.encode(broken))
                fail("Accepted missing legacy column ${target.table.name}.${target.table.columns[columnIndex].name}")
            } catch (_: BackupException) { }
        }
    }
}
