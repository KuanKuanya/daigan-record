package com.daigan.record.data.local

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Legacy rows are inserted using the immutable exported v7 schema. */
@RunWith(AndroidJUnit4::class)
class MigrationV7ToV8Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "migration-v7-to-v8-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RecordDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun tearDown() { context.deleteDatabase(databaseName) }

    @Test
    fun everyLegacyColumnAndRowSurvivesWithNewNullableFieldsUnset() {
        lateinit var columns: Map<String, List<String>>
        val before = helper.createDatabase(databaseName, 7).use { database ->
            insertPhaseThreeFixture(database)
            insertTemplates(database)
            database.execSQL("INSERT INTO reminder_settings VALUES (0, 1, 321, 0, 654, 1, 987, 1700000300000)")
            database.execSQL("INSERT INTO app_settings VALUES (0, 'legacy-dataset', 'legacy-restore', 1700000300000)")
            database.execSQL("INSERT INTO reminder_deliveries VALUES ('SHORT_DEADLINE', '2026-10-18', 1700000300000)")
            columns = legacyColumns(database)
            snapshot(database, columns)
        }
        helper.runMigrationsAndValidate(databaseName, 8, true, RecordDatabase.MIGRATION_7_8).use { database ->
            assertEquals(before, snapshot(database, columns))
            for ((table, column) in listOf(
                "orders" to "cancelledAt",
                "task_definitions" to "monthDay",
                "template_tasks" to "monthDay",
            )) {
                database.query("SELECT `$column` FROM `$table`").use { cursor ->
                    assertTrue("fixture must exercise $table", cursor.moveToFirst())
                    do { assertTrue("legacy $table.$column must remain NULL", cursor.isNull(0)) }
                    while (cursor.moveToNext())
                }
            }
            database.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test
    fun everyHistoricalExportMigratesThroughTheShippedChainToEight() {
        for (version in 1..7) {
            context.deleteDatabase(databaseName)
            helper.createDatabase(databaseName, version).close()
            helper.runMigrationsAndValidate(databaseName, 8, true, *RecordDatabase.ALL_MIGRATIONS)
                .use { database -> assertEquals(8, database.version) }
        }
    }

    private fun legacyColumns(database: SupportSQLiteDatabase): Map<String, List<String>> {
        val tables = database.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        return tables.associateWith { table ->
            database.query("PRAGMA table_info(`$table`)").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
        }
    }

    private fun snapshot(
        database: SupportSQLiteDatabase,
        columns: Map<String, List<String>>,
    ): Map<String, List<List<String?>>> = columns.mapValues { (table, names) ->
        val projection = names.joinToString(", ") { "`$it`" }
        database.query("SELECT $projection FROM `$table` ORDER BY $projection").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).map { index ->
                        if (cursor.isNull(index)) null else cursor.getString(index)
                    })
                }
            }
        }
    }

    private fun insertTemplates(database: SupportSQLiteDatabase) {
        database.execSQL("""
            INSERT INTO game_templates VALUES
            ('tpl-1', '自定义游戏', 'v4-edited', '用户编辑保留', 'Asia/Shanghai',
             240, 2, 300, 'CUSTOM', NULL, 8, 1700000000000, 1700000200000)
        """.trimIndent())
        database.execSQL("INSERT INTO template_categories VALUES ('category-1', 'tpl-1', '日常', 3)")
        database.execSQL("""
            INSERT INTO template_tasks VALUES
            ('tpl-task-1', 'tpl-1', 'category-1', '累计材料', 'NUMBER', '2.5', '100.5',
             '个', 'EVERY_N_DAYS', NULL, 3, 7)
        """.trimIndent())
        database.execSQL("INSERT INTO template_seed_state VALUES ('builtin-v1', 1700000000000)")
        database.execSQL("UPDATE game_accounts SET defaultTemplateId = 'tpl-1', archivedAt = 1700000200000, archiveBatchId = 'archive-1'")
        database.execSQL("UPDATE owners SET archivedAt = 1700000200000, archiveBatchId = 'archive-1'")
        database.execSQL("UPDATE orders SET sourceTemplateId = 'tpl-1', sourceTemplateName = '旧模板名', sourceTemplateVersion = 'v3'")
    }

    private fun insertPhaseThreeFixture(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            INSERT INTO owners
                (id, number, nickname, createdAt, updatedAt, archivedAt, archiveBatchId)
            VALUES ('owner-1', 1, '阶段三老板', 1700000000000, 1700000000000, NULL, NULL)
            """.trimIndent(),
        )
        database.execSQL("INSERT INTO owner_number_sequence (id, nextNumber) VALUES (0, 2)")
        database.execSQL(
            """
            INSERT INTO game_accounts
                (id, ownerId, gameName, server, uid, loginAccount, loginMethod, password,
                 createdAt, updatedAt, archivedAt, archiveBatchId)
            VALUES ('account-1', 'owner-1', '重返未来：1999', '官服', '10000628',
                    'phase3-login', 'PASSWORD', 'secret', 1700000000000, 1700000000000,
                    NULL, NULL)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO orders
                (id, accountId, type, title, priceCents, paymentMode, deadlineDate, notes,
                 gameZoneId, dailyRefreshMinutes, weeklyRefreshDay, weeklyRefreshMinutes,
                 createdAt, updatedAt, completedAt)
            VALUES ('order-short', 'account-1', 'SHORT_TASK', '阶段三短期单', 12345,
                    'INSTALLMENTS', '2026-10-30', '历史备注', 'Asia/Shanghai', 240, 1, 300,
                    1700000000000, 1700000000000, NULL)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO orders
                (id, accountId, type, title, priceCents, paymentMode, deadlineDate, notes,
                 gameZoneId, dailyRefreshMinutes, weeklyRefreshDay, weeklyRefreshMinutes,
                 createdAt, updatedAt, completedAt)
            VALUES ('order-hosting', 'account-1', 'HOSTING', '阶段三托管单', NULL,
                    'ONE_TIME', NULL, NULL, 'Asia/Shanghai', 240, 1, 300,
                    1700000001000, 1700000001000, NULL)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO hosting_periods
                (id, orderId, periodIndex, startDate, endDate, priceCents, gameZoneId,
                 dailyRefreshMinutes, weeklyRefreshDay, weeklyRefreshMinutes, createdAt,
                 renewalOperationId)
            VALUES ('period-1', 'order-hosting', 0, '2026-10-01', '2026-10-10', 30000,
                    'Asia/Shanghai', 240, 1, 300, 1700000001000, NULL)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO task_definitions
                (id, orderId, periodId, title, progressType, startValue, targetValue,
                 currentValue, unit, recurrence, weekdays, everyNDays, sortOrder,
                 completedAt, completionBatchId, createdAt, updatedAt)
            VALUES ('task-short', 'order-short', NULL, '推进主线', 'CHECKBOX', '0', '1', '0',
                    NULL, 'DAILY', NULL, NULL, 0, NULL, NULL, 1700000001000, 1700000001000)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO task_definitions
                (id, orderId, periodId, title, progressType, startValue, targetValue,
                 currentValue, unit, recurrence, weekdays, everyNDays, sortOrder,
                 completedAt, completionBatchId, createdAt, updatedAt)
            VALUES ('task-hosting', 'order-hosting', 'period-1', '每日签到', 'CHECKBOX', '0',
                    '1', '0', NULL, 'DAILY', NULL, NULL, 0, NULL, NULL, 1700000001000,
                    1700000001000)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO completion_batches
                (id, operationId, scope, targetId, gameDate, createdAt, undoneAt)
            VALUES ('batch-1', 'op-batch-1', 'HOSTING_ORDER', 'order-hosting',
                    '2026-10-05', 1700000100000, NULL)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO task_occurrences
                (id, taskDefinitionId, occurrenceKey, gameDate, windowStart, windowEnd,
                 completedAt, recordedAt, backfilled, completionBatchId, value, createdAt)
            VALUES ('occ-1', 'task-hosting', 'D:2026-10-05', '2026-10-05', '2026-10-05',
                    '2026-10-05', 1700000100000, 1700000100000, 0, 'batch-1', NULL,
                    1700000100000)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO completion_batch_items
                (id, batchId, itemType, occurrenceId, taskDefinitionId, previousCompletedAt,
                 previousRecordedAt, previousBackfilled, previousValue,
                 previousCompletionBatchId, appliedCompletedAt, appliedRecordedAt,
                 appliedBackfilled, appliedValue, appliedCompletionBatchId)
            VALUES ('item-1', 'batch-1', 'OCCURRENCE', 'occ-1', 'task-hosting', NULL, NULL,
                    NULL, NULL, NULL, 1700000100000, 1700000100000, 0, NULL, 'batch-1')
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO attachments
                (id, taskDefinitionId, occurrenceId, relativePath, mimeType, sizeBytes, createdAt)
            VALUES ('attachment-1', NULL, 'occ-1', 'attachments/shot.jpg', 'image/jpeg', 4096,
                    1700000100000)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO receipts
                (id, operationId, orderId, periodId, amountCents, receivedAt, channel, note,
                 status, replacedByReceiptId, createdAt)
            VALUES ('receipt-1', 'op-receipt-1', 'order-short', NULL, 10000,
                    1700000100000, 'WECHAT', NULL, 'ACTIVE', NULL, 1700000100000)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO receipt_corrections
                (id, operationId, receiptId, kind, reason, previousAmountCents,
                 previousReceivedAt, previousChannel, previousNote, newReceiptId,
                 newAmountCents, newReceivedAt, newChannel, newNote, createdAt)
            VALUES ('correction-1', 'op-correction-1', 'receipt-1', 'AMEND', '补记',
                    10000, 1700000100000, 'WECHAT', NULL, NULL, 12000, 1700000100000,
                    'WECHAT', NULL, 1700000200000)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO order_creation_operations
                (operationId, orderType, fingerprint, orderId, receiptId, createdAt)
            VALUES ('op-create-1', 'SHORT_TASK', 'fingerprint-1', 'order-short', 'receipt-1',
                    1700000000000)
            """.trimIndent(),
        )
    }

}
