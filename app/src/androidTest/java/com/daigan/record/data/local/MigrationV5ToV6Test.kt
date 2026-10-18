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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Version 5 -> 6 with **real Phase 3 data**: owners, accounts, short/hosting orders,
 * a period, tasks, an occurrence, a completion batch, a screenshot reference, an active
 * receipt, an amending correction and a create-order operation.
 *
 * The starting schema is the committed `5.json`, i.e. the exact DDL an accepted Phase 3
 * installation had on disk. Nothing may be rewritten: the template tables start empty,
 * legacy orders keep NULL provenance and legacy accounts keep no default template.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV5ToV6Test {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "migration-v5-to-v6-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation,
        RecordDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun phaseThreeFinancialAndCreationDataSurvivesWithoutTemplateProvenance() {
        helper.createDatabase(databaseName, 5).use { database ->
            insertPhaseThreeFixture(database)
        }

        helper.runMigrationsAndValidate(
            databaseName,
            RECORD_DATABASE_VERSION,
            true,
            *RecordDatabase.ALL_MIGRATIONS,
        ).use { database ->
            assertEquals(1, count(database, "owners"))
            assertEquals(1, count(database, "game_accounts"))
            assertEquals(2, count(database, "orders"))
            assertEquals(1, count(database, "hosting_periods"))
            assertEquals(2, count(database, "task_definitions"))
            assertEquals(1, count(database, "task_occurrences"))
            assertEquals(1, count(database, "completion_batches"))
            assertEquals(1, count(database, "completion_batch_items"))
            assertEquals(1, count(database, "attachments"))
            assertEquals(1, count(database, "receipts"))
            assertEquals(1, count(database, "receipt_corrections"))
            assertEquals(1, count(database, "order_creation_operations"))

            database.query(
                "SELECT title, priceCents, paymentMode, sourceTemplateId, sourceTemplateName, " +
                    "sourceTemplateVersion FROM orders WHERE id = 'order-short'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("阶段三短期单", cursor.getString(0))
                assertEquals(12345L, cursor.getLong(1))
                assertEquals("INSTALLMENTS", cursor.getString(2))
                assertTrue("legacy orders must not get invented provenance", cursor.isNull(3))
                assertTrue(cursor.isNull(4))
                assertTrue(cursor.isNull(5))
            }
            database.query(
                "SELECT amountCents, receivedAt, channel, status, note FROM receipts " +
                    "WHERE id = 'receipt-1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(10000L, cursor.getLong(0))
                assertEquals(1700000100000L, cursor.getLong(1))
                assertEquals("WECHAT", cursor.getString(2))
                assertEquals("ACTIVE", cursor.getString(3))
                assertTrue(cursor.isNull(4))
            }
            database.query(
                "SELECT kind, previousAmountCents, newAmountCents FROM receipt_corrections " +
                    "WHERE id = 'correction-1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("AMEND", cursor.getString(0))
                assertEquals(10000L, cursor.getLong(1))
                assertEquals(12000L, cursor.getLong(2))
            }
            database.query(
                "SELECT operationId, orderId, receiptId FROM order_creation_operations",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("op-create-1", cursor.getString(0))
                assertEquals("order-short", cursor.getString(1))
                assertEquals("receipt-1", cursor.getString(2))
            }
            database.query(
                "SELECT defaultTemplateId, archiveBatchId FROM game_accounts WHERE id = 'account-1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue("legacy accounts must not be assigned a template", cursor.isNull(0))
                assertTrue(cursor.isNull(1))
            }

            // The new template library is empty: the migration never fabricates content.
            assertEquals(0, count(database, "game_templates"))
            assertEquals(0, count(database, "template_categories"))
            assertEquals(0, count(database, "template_tasks"))
            assertEquals(0, count(database, "template_seed_state"))

            // Template children cannot reference a missing template, and RESTRICT keeps
            // template rows deletable only after their content is gone.
            database.execSQL("PRAGMA foreign_keys = ON")
            assertNotNull(
                "a template category must reference a real template",
                runCatching {
                    database.execSQL(
                        "INSERT INTO template_categories (id, templateId, name, sortOrder) " +
                            "VALUES ('c1', 'missing-template', '日常', 0)",
                    )
                }.exceptionOrNull(),
            )
        }
    }

    @Test
    fun deletingATemplateNeverTouchesExistingOrders() {
        helper.createDatabase(databaseName, 5).use { insertPhaseThreeFixture(it) }

        helper.runMigrationsAndValidate(
            databaseName,
            RECORD_DATABASE_VERSION,
            true,
            *RecordDatabase.ALL_MIGRATIONS,
        ).use { database ->
            database.execSQL("PRAGMA foreign_keys = ON")
            database.execSQL(
                """
                INSERT INTO game_templates
                    (id, gameName, templateVersion, gameVersionNotes, zoneId,
                     dailyRefreshMinutes, weeklyRefreshDay, weeklyRefreshMinutes, origin,
                     builtInKey, sortOrder, createdAt, updatedAt)
                VALUES ('tpl-1', '重返未来：1999', '2026.10', NULL, 'Asia/Shanghai',
                        300, 1, 300, 'BUILT_IN', 'reverse-1999', 0, 1700000000000,
                        1700000000000)
                """.trimIndent(),
            )
            database.execSQL(
                "UPDATE orders SET sourceTemplateId = 'tpl-1', sourceTemplateName = '重返未来：1999', " +
                    "sourceTemplateVersion = '2026.10' WHERE id = 'order-short'",
            )
            database.execSQL(
                "UPDATE game_accounts SET defaultTemplateId = 'tpl-1' WHERE id = 'account-1'",
            )

            database.execSQL("DELETE FROM game_templates WHERE id = 'tpl-1'")

            // Provenance is plain text, not a foreign key: the historical label survives.
            database.query(
                "SELECT sourceTemplateId, sourceTemplateName, sourceTemplateVersion, " +
                    "priceCents FROM orders WHERE id = 'order-short'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("tpl-1", cursor.getString(0))
                assertEquals("重返未来：1999", cursor.getString(1))
                assertEquals("2026.10", cursor.getString(2))
                assertEquals(12345L, cursor.getLong(3))
            }
            assertEquals(1, count(database, "receipts"))
            database.query("SELECT defaultTemplateId FROM game_accounts WHERE id = 'account-1'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    // Deleting through SQL bypasses the repository's cleanup, so this
                    // documents the raw schema (plain TEXT) rather than the repository
                    // contract; TemplateRepositoryTest covers the cleanup path.
                    assertNotNull(cursor.getString(0))
                }
        }
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

    private fun count(database: SupportSQLiteDatabase, table: String): Int =
        database.query("SELECT COUNT(*) FROM $table").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }
}
