package com.kuankuan.daigan.data.local

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
 * A database is created from the committed `schemas/.../1.json` (the exact version 1
 * DDL an installed app had on disk), filled with raw rows, and then upgraded by the
 * shipped migrations. Room validates the result against the current schema and the
 * rows must still be there.
 *
 * The version specific assertions live in `MigrationV1ToV2Test`; this test keeps the
 * harness itself checked against the current database version.
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
    fun historicalFixtureIsUpgradedAndValidatedWithoutDataLoss() {
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

        val validated = helper.runMigrationsAndValidate(
            databaseName,
            RECORD_DATABASE_VERSION,
            true,
            *RecordDatabase.ALL_MIGRATIONS,
        )

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
    fun exportedSchemasForEveryVersionExistInAssets() {
        val assets = instrumentation.context.assets

        for (version in 1..RECORD_DATABASE_VERSION) {
            val schemaPath = "com.kuankuan.daigan.data.local.RecordDatabase/$version.json"
            val schema = assets.open(schemaPath).bufferedReader().use { it.readText() }

            assertTrue(schema.contains("\"version\": $version"))
            assertTrue(schema.contains("\"tableName\": \"owners\""))
            assertTrue(schema.contains("\"tableName\": \"game_accounts\""))
        }
    }
}
