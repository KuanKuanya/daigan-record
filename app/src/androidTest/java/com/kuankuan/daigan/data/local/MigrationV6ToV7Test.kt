package com.kuankuan.daigan.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.room.util.TableInfo
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kuankuan.daigan.data.local.entity.AppSettingsEntity
import com.kuankuan.daigan.data.local.entity.ReminderDeliveryEntity
import com.kuankuan.daigan.core.time.TimeProvider
import com.kuankuan.daigan.notifications.DefaultReminderSettingsRepository
import com.kuankuan.daigan.notifications.ReminderSettings
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the immutable exported v6 schema, never a current entity to construct legacy data. */
@RunWith(AndroidJUnit4::class)
class MigrationV6ToV7Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "migration-v6-to-v7-test.db"
    private val freshName = "migration-v7-fresh-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RecordDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
        context.deleteDatabase(freshName)
    }

    @Test
    fun allPhaseFourRowsAndEveryColumnSurviveMigration() {
        val before = helper.createDatabase(databaseName, 6).use { database ->
            insertPhaseThreeFixture(database)
            insertTemplates(database)
            snapshot(database)
        }
        helper.runMigrationsAndValidate(databaseName, 7, true, RecordDatabase.MIGRATION_6_7)
            .use { database ->
                assertEquals(before, snapshot(database))
                for (table in settingsTables) {
                    database.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("migration must not invent local state in $table", 0, cursor.getInt(0))
                    }
                }
                database.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            }
    }

    @Test
    fun freshAndMigratedSettingsHaveSameSchemaDisabledDefaultsAndDurablePreferences() = runBlocking {
        helper.createDatabase(databaseName, 6).close()
        helper.runMigrationsAndValidate(databaseName, 7, true, RecordDatabase.MIGRATION_6_7).close()
        val expected = ReminderSettings(
            shortDeadlineEnabled = true,
            shortDeadlineMinutes = 321,
            hostingExpiryEnabled = false,
            hostingExpiryMinutes = 654,
            unfinishedTasksEnabled = true,
            unfinishedTasksMinutes = 987,
        )
        val fresh = open(freshName)
        val migrated = open(databaseName)
        try {
            for (table in settingsTables) {
                assertEquals(
                    TableInfo.read(fresh.openHelper.writableDatabase, table),
                    TableInfo.read(migrated.openHelper.writableDatabase, table),
                )
            }
            for (database in listOf(fresh, migrated)) {
                val repository = settingsRepository(database)
                assertEquals(ReminderSettings(), repository.load())
                assertFalse(repository.load().anyEnabled)
                repository.save(expected)
                database.appSettingsDao().upsert(
                    AppSettingsEntity(datasetInstanceId = "dataset-7", lastRestoreId = "restore-7", createdAt = Instant.EPOCH),
                )
                database.reminderDeliveryDao().insert(
                    ReminderDeliveryEntity("SHORT_DEADLINE", "2026-10-18", Instant.EPOCH),
                )
            }
        } finally {
            fresh.close()
            migrated.close()
        }
        for (name in listOf(freshName, databaseName)) {
            val database = open(name)
            try {
                assertEquals(expected, settingsRepository(database).load())
                assertEquals("dataset-7", database.appSettingsDao().datasetInstanceId())
                assertEquals("restore-7", database.appSettingsDao().lastRestoreId())
                assertEquals(Instant.EPOCH, database.reminderDeliveryDao().deliveredAt("SHORT_DEADLINE", "2026-10-18"))
                assertEquals(Instant.ofEpochMilli(1700000300000), database.reminderSettingsDao().find()!!.updatedAt)
            } finally { database.close() }
        }
    }

    @Test
    fun everyHistoricalExportMigratesThroughTheShippedChainToSeven() {
        for (version in 1..6) {
            context.deleteDatabase(databaseName)
            helper.createDatabase(databaseName, version).close()
            helper.runMigrationsAndValidate(databaseName, 7, true, *RecordDatabase.ALL_MIGRATIONS)
                .use { database ->
                    assertEquals(7, database.version)
                    for (table in settingsTables) assertTrue(TableInfo.read(database, table).columns.isNotEmpty())
                }
        }
    }

    private fun open(name: String): RecordDatabase =
        Room.databaseBuilder(context, RecordDatabase::class.java, name)
            .addMigrations(*RecordDatabase.ALL_MIGRATIONS).build()

    private fun settingsRepository(database: RecordDatabase) = DefaultReminderSettingsRepository(
        database.reminderSettingsDao(), TimeProvider { Instant.ofEpochMilli(1700000300000) },
    )

    private val settingsTables = listOf("reminder_settings", "app_settings", "reminder_deliveries")
    private val businessTables = listOf(
        "owners", "owner_number_sequence", "game_accounts", "orders", "hosting_periods",
        "task_definitions", "task_occurrences", "completion_batches", "completion_batch_items",
        "attachments", "receipts", "receipt_corrections", "order_creation_operations",
        "game_templates", "template_categories", "template_tasks", "template_seed_state",
    )

    // Preserve NULLs and all columns, including credentials, archive markers, money,
    // operation fingerprints, occurrence windows and template provenance.
    private fun snapshot(database: SupportSQLiteDatabase): Map<String, List<List<String?>>> =
        businessTables.associateWith { table ->
            database.query("SELECT * FROM `$table` ORDER BY 1").use { cursor ->
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
