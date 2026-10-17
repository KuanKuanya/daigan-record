package com.daigan.record.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration test infrastructure.
 *
 * Phase 1 ships database version 1, so there is no production upgrade to fake here.
 * Instead this test proves the harness works end to end with an isolated historical
 * schema fixture: the committed `schemas/.../1.json` behaves as the historical schema,
 * a database is created from it, rows are written with raw SQL, and Room then validates
 * that the current schema still matches the fixture without losing data.
 *
 * When Phase 2 (or later) bumps the schema version, a real migration test can be added
 * by copying the old exported JSON, creating that version, and calling
 * `runMigrationsAndValidate(dbName, <newVersion>, true, MIGRATION_X_Y)`.
 */
@RunWith(AndroidJUnit4::class)
class MigrationInfrastructureTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val databaseName = "migration-infrastructure-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation,
        RecordDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun tearDown() {
        instrumentation.targetContext.deleteDatabase(databaseName)
    }

    @Test
    fun historicalFixtureIsCreatedAndValidatedWithoutDataLoss() {
        helper.createDatabase(databaseName, 1).use { database ->
            database.execSQL(
                """
                INSERT INTO owners (id, number, nickname, createdAt, updatedAt, archivedAt)
                VALUES ('owner-1', 1, '小满', 1000, 1000, NULL)
                """.trimIndent(),
            )
            database.execSQL(
                "INSERT INTO owner_number_sequence (id, nextNumber) VALUES (0, 2)",
            )
            database.execSQL(
                """
                INSERT INTO game_accounts
                    (id, ownerId, gameName, server, uid, loginAccount, loginMethod,
                     password, createdAt, updatedAt, archivedAt)
                VALUES ('account-1', 'owner-1', '重返未来：1999', '官服', '10000628',
                        'demo-xiaoman', 'PASSWORD', 'demo1999', 1000, 1000, NULL)
                """.trimIndent(),
            )
        }

        val validated = helper.runMigrationsAndValidate(databaseName, 1, true)

        validated.query("SELECT nickname, number FROM owners WHERE id = 'owner-1'").use { cursor ->
            assertTrue("Fixture row must survive schema validation", cursor.moveToFirst())
            assertEquals("小满", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
        }
        validated.query("SELECT loginMethod, password FROM game_accounts").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("PASSWORD", cursor.getString(0))
            assertEquals("demo1999", cursor.getString(1))
        }
        validated.query("SELECT nextNumber FROM owner_number_sequence WHERE id = 0").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
        validated.close()
    }

    @Test
    fun exportedSchemaForVersionOneExistsInAssets() {
        val assets = instrumentation.context.assets
        val schemaPath = "com.daigan.record.data.local.RecordDatabase/1.json"

        val schema = assets.open(schemaPath).bufferedReader().use { it.readText() }

        assertTrue(schema.contains("\"version\": 1"))
        assertTrue(schema.contains("\"tableName\": \"owners\""))
        assertTrue(schema.contains("\"tableName\": \"game_accounts\""))
    }
}
