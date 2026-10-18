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
 * Version 2 -> 3 preserves owners, numbers, credentials and archive provenance while
 * adding the Phase 2 tables. The fixture is the committed `2.json`, so the starting
 * schema is exactly what an accepted Phase 1 installation had on disk.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV2ToV3Test {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "migration-v2-to-v3-test.db"

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
    fun versionTwoRowsAndArchiveProvenanceSurviveTheMigration() {
        helper.createDatabase(databaseName, 2).use { database ->
            database.execSQL(
                """
                INSERT INTO owners
                    (id, number, nickname, createdAt, updatedAt, archivedAt, archiveBatchId)
                VALUES ('owner-active', 1, '在用老板', 1000, 1000, NULL, NULL)
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO owners
                    (id, number, nickname, createdAt, updatedAt, archivedAt, archiveBatchId)
                VALUES ('owner-archived', 2, '归档老板', 1000, 2000, 2000, 'batch-owner')
                """.trimIndent(),
            )
            database.execSQL("INSERT INTO owner_number_sequence (id, nextNumber) VALUES (0, 3)")
            database.execSQL(
                """
                INSERT INTO game_accounts
                    (id, ownerId, gameName, server, uid, loginAccount, loginMethod,
                     password, createdAt, updatedAt, archivedAt, archiveBatchId)
                VALUES ('account-active', 'owner-active', '鸣潮', '官服', 'uid-active',
                        'login-active', 'PASSWORD', 'pass-active', 1000, 1000, NULL, NULL)
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO game_accounts
                    (id, ownerId, gameName, server, uid, loginAccount, loginMethod,
                     password, createdAt, updatedAt, archivedAt, archiveBatchId)
                VALUES ('account-with-owner', 'owner-archived', '重返未来：1999', '官服',
                        'uid-with-owner', 'login-owner', 'PASSWORD', 'pass-owner',
                        1000, 2000, 2000, 'batch-owner')
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(
            databaseName,
            RECORD_DATABASE_VERSION,
            true,
            *RecordDatabase.ALL_MIGRATIONS,
        ).use { database ->
            assertEquals(2, count(database, "owners"))
            assertEquals(2, count(database, "game_accounts"))
            assertEquals(3, scalarInt(database, "SELECT nextNumber FROM owner_number_sequence WHERE id = 0"))

            database.query(
                "SELECT nickname, number, archivedAt, archiveBatchId FROM owners WHERE id = 'owner-archived'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("归档老板", cursor.getString(0))
                assertEquals(2, cursor.getInt(1))
                assertEquals(2000L, cursor.getLong(2))
                assertEquals("batch-owner", cursor.getString(3))
            }
            database.query(
                "SELECT uid, loginMethod, password, archiveBatchId FROM game_accounts " +
                    "WHERE id = 'account-with-owner'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("uid-with-owner", cursor.getString(0))
                assertEquals("PASSWORD", cursor.getString(1))
                assertEquals("pass-owner", cursor.getString(2))
                assertEquals("batch-owner", cursor.getString(3))
            }

            // The new tables exist and start empty: no fabricated orders or receipts.
            assertEquals(0, count(database, "orders"))
            assertEquals(0, count(database, "hosting_periods"))
            assertEquals(0, count(database, "task_definitions"))
            assertEquals(0, count(database, "task_occurrences"))
            assertEquals(0, count(database, "completion_batches"))
            assertEquals(0, count(database, "completion_batch_items"))
            assertEquals(0, count(database, "attachments"))

            // Foreign keys are part of the new schema and protect business history.
            database.execSQL("PRAGMA foreign_keys = ON")
            database.execSQL(
                """
                INSERT INTO orders
                    (id, accountId, type, title, priceCents, paymentMode, deadlineDate,
                     notes, gameZoneId, dailyRefreshMinutes, weeklyRefreshDay,
                     weeklyRefreshMinutes, createdAt, updatedAt, completedAt)
                VALUES ('order-1', 'account-active', 'SHORT_TASK', '迁移后订单', 10000,
                        'ONE_TIME', NULL, NULL, 'Asia/Shanghai', 240, 1, 300, 3000, 3000, NULL)
                """.trimIndent(),
            )
            database.query("SELECT accountId FROM orders WHERE id = 'order-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("account-active", cursor.getString(0))
            }
            assertNotNull(
                "RESTRICT must prevent deleting an account that still owns orders",
                runCatching {
                    database.execSQL("DELETE FROM game_accounts WHERE id = 'account-active'")
                }.exceptionOrNull(),
            )
        }
    }

    @Test
    fun versionThreeSchemaKeepsTheV2Columns() {
        val schema = instrumentation.context.assets
            .open("com.daigan.record.data.local.RecordDatabase/3.json")
            .bufferedReader()
            .use { it.readText() }
        assertTrue(schema.contains("\"version\": 3"))
        assertTrue(schema.contains("\"columnName\": \"archiveBatchId\""))
        assertTrue(schema.contains("\"tableName\": \"owners\""))
        assertTrue(schema.contains("\"tableName\": \"game_accounts\""))
        assertTrue(schema.contains("\"tableName\": \"orders\""))
    }

    private fun count(database: SupportSQLiteDatabase, table: String): Int =
        scalarInt(database, "SELECT COUNT(*) FROM $table")

    private fun scalarInt(database: SupportSQLiteDatabase, sql: String): Int =
        database.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }
}
