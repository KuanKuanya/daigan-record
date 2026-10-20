package com.kuankuan.daigan.data.local

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
 * Version 3 -> 5 with **real Phase 2 business data**: owners, accounts, orders,
 * hosting periods, tasks, occurrences, completion batches/items and attachment
 * references. Nothing may be rewritten and no receipt may be fabricated for a price.
 *
 * The starting schema is the committed `3.json`, i.e. the exact DDL an accepted
 * Phase 2 installation had on disk. `4.json` is used for the 4 -> 5 step.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV3ToV5Test {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "migration-v3-to-v5-test.db"

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
    fun phaseTwoBusinessDataSurvivesAndNoReceiptIsInvented() {
        helper.createDatabase(databaseName, 3).use { database ->
            insertOwnerAndAccount(database)
            database.execSQL(
                """
                INSERT INTO orders
                    (id, accountId, type, title, priceCents, paymentMode, deadlineDate, notes,
                     gameZoneId, dailyRefreshMinutes, weeklyRefreshDay, weeklyRefreshMinutes,
                     createdAt, updatedAt, completedAt)
                VALUES ('order-short', 'account-1', 'SHORT_TASK', '迁移短期单', 12345,
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
                VALUES ('order-hosting', 'account-1', 'HOSTING', '迁移托管单', NULL,
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
                VALUES ('task-hosting', 'order-hosting', 'period-1', '每日签到', 'CHECKBOX', '0', '1',
                        '0', NULL, 'DAILY', NULL, NULL, 0, NULL, NULL, 1700000001000, 1700000001000)
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

            database.query(
                "SELECT title, priceCents, paymentMode, notes FROM orders WHERE id = 'order-short'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("迁移短期单", cursor.getString(0))
                assertEquals(12345L, cursor.getLong(1))
                assertEquals("INSTALLMENTS", cursor.getString(2))
                assertEquals("历史备注", cursor.getString(3))
            }
            database.query(
                "SELECT priceCents, startDate, endDate FROM hosting_periods WHERE id = 'period-1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(30000L, cursor.getLong(0))
                assertEquals("2026-10-01", cursor.getString(1))
                assertEquals("2026-10-10", cursor.getString(2))
            }
            database.query(
                "SELECT relativePath, mimeType FROM attachments WHERE id = 'attachment-1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("attachments/shot.jpg", cursor.getString(0))
                assertEquals("image/jpeg", cursor.getString(1))
            }
            database.query(
                "SELECT completedAt, completionBatchId FROM task_occurrences WHERE id = 'occ-1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1700000100000L, cursor.getLong(0))
                assertEquals("batch-1", cursor.getString(1))
            }

            // Phase 3 tables exist but are empty: a promised price is not a payment.
            assertEquals(0, count(database, "receipts"))
            assertEquals(0, count(database, "receipt_corrections"))
            assertEquals(0, count(database, "order_creation_operations"))

            // The new receipt foreign keys are active.
            database.execSQL("PRAGMA foreign_keys = ON")
            assertNotNull(
                "RESTRICT must protect an order that owns receipts",
                runCatching {
                    database.execSQL("DELETE FROM orders WHERE id = 'order-short'")
                }.exceptionOrNull(),
            )
        }
    }

    @Test
    fun versionFourOrdersKeepWorkingWithoutASyntheticCreationOperation() {
        helper.createDatabase(databaseName, 4).use { database ->
            insertOwnerAndAccount(database)
            database.execSQL(
                """
                INSERT INTO orders
                    (id, accountId, type, title, priceCents, paymentMode, deadlineDate, notes,
                     gameZoneId, dailyRefreshMinutes, weeklyRefreshDay, weeklyRefreshMinutes,
                     createdAt, updatedAt, completedAt)
                VALUES ('order-v4', 'account-1', 'SHORT_TASK', 'v4 订单', 9900,
                        'ONE_TIME', NULL, NULL, 'Asia/Shanghai', 240, 1, 300,
                        1700000200000, 1700000200000, NULL)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(
            databaseName,
            RECORD_DATABASE_VERSION,
            true,
            *RecordDatabase.ALL_MIGRATIONS,
        ).use { database ->
            database.query("SELECT title, priceCents FROM orders WHERE id = 'order-v4'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("v4 订单", cursor.getString(0))
                assertEquals(9900L, cursor.getLong(1))
            }
            assertEquals(
                "An existing order must not get an invented command identity",
                0,
                count(database, "order_creation_operations"),
            )
            assertEquals(0, count(database, "receipts"))

            database.execSQL("PRAGMA foreign_keys = ON")
            assertNotNull(
                "A creation operation must reference a real order",
                runCatching {
                    database.execSQL(
                        """
                        INSERT INTO order_creation_operations
                            (operationId, orderType, fingerprint, orderId, receiptId, createdAt)
                        VALUES ('missing', 'SHORT_TASK', 'fp', 'no-such-order', NULL, 1)
                        """.trimIndent(),
                    )
                }.exceptionOrNull(),
            )
        }
    }

    private fun insertOwnerAndAccount(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            INSERT INTO owners
                (id, number, nickname, createdAt, updatedAt, archivedAt, archiveBatchId)
            VALUES ('owner-1', 1, '迁移老板', 1700000000000, 1700000000000, NULL, NULL)
            """.trimIndent(),
        )
        database.execSQL("INSERT INTO owner_number_sequence (id, nextNumber) VALUES (0, 2)")
        database.execSQL(
            """
            INSERT INTO game_accounts
                (id, ownerId, gameName, server, uid, loginAccount, loginMethod, password,
                 createdAt, updatedAt, archivedAt, archiveBatchId)
            VALUES ('account-1', 'owner-1', '重返未来：1999', '官服', '10000628',
                    'migrate-login', 'PASSWORD', 'secret', 1700000000000, 1700000000000,
                    NULL, NULL)
            """.trimIndent(),
        )
    }

    private fun count(database: SupportSQLiteDatabase, table: String): Int =
        database.query("SELECT COUNT(*) FROM $table").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }
}
