package com.kuankuan.daigan.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kuankuan.daigan.core.time.TimeProvider
import com.kuankuan.daigan.data.repository.DefaultGameAccountRepository
import com.kuankuan.daigan.data.repository.DefaultOwnerRepository
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real version 1 -> version 2 migration tests.
 *
 * The fixture is the committed `schemas/.../1.json`, so the rows are inserted with the
 * exact version 1 DDL an installed app had on disk. The migration only adds nullable
 * provenance columns; every legacy row keeps its owner, number, account, credential and
 * archive timestamp, and `archiveBatchId` stays NULL because provenance cannot be
 * recovered from the old schema.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV1ToV2Test {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "migration-v1-to-v2-test.db"

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
    fun versionOneRowsSurviveTheMigrationWithAllDataPreserved() {
        helper.createDatabase(databaseName, 1).use { database ->
            insertLegacyFixture(database)
        }

        helper.runMigrationsAndValidate(
            databaseName,
            RECORD_DATABASE_VERSION,
            true,
            *RecordDatabase.ALL_MIGRATIONS,
        ).use { database ->
            database.query("SELECT COUNT(*) FROM owners").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Both owners must survive", 2, cursor.getInt(0))
            }
            database.query("SELECT COUNT(*) FROM game_accounts").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("All accounts must survive", 3, cursor.getInt(0))
            }

            assertOwner(
                database = database,
                id = "owner-active",
                number = 1,
                nickname = "在用老板",
                archivedAt = null,
            )
            assertOwner(
                database = database,
                id = "owner-archived",
                number = 2,
                nickname = "归档老板",
                archivedAt = 2_000L,
            )
            assertAccount(
                database = database,
                id = "account-active",
                ownerId = "owner-active",
                uid = "uid-active",
                loginMethod = "PASSWORD",
                password = "pass-active",
                archivedAt = null,
            )
            assertAccount(
                database = database,
                id = "account-with-owner",
                ownerId = "owner-archived",
                uid = "uid-with-owner",
                loginMethod = "PASSWORD",
                password = "pass-owner",
                archivedAt = 2_000L,
            )
            assertAccount(
                database = database,
                id = "account-independent",
                ownerId = "owner-archived",
                uid = "uid-independent",
                loginMethod = "VERIFICATION_CODE",
                password = null,
                archivedAt = 2_000L,
            )

            database.query("SELECT nextNumber FROM owner_number_sequence WHERE id = 0").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(3, cursor.getInt(0))
            }

            database.query(
                "SELECT COUNT(*) FROM owners WHERE archiveBatchId IS NOT NULL",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("No provenance may be invented during the migration", 0, cursor.getInt(0))
            }
            database.query(
                "SELECT COUNT(*) FROM game_accounts WHERE archiveBatchId IS NOT NULL",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("No provenance may be invented during the migration", 0, cursor.getInt(0))
            }
        }
    }

    /**
     * The documented conservative policy: for legacy rows the original archive
     * provenance is unknown, so restoring the owner restores only the owner. Every
     * account stays archived and available for explicit manual restoration.
     */
    @Test
    fun legacyOwnerRestoreKeepsAmbiguousAccountsArchivedForManualRestore() = runBlocking {
        helper.createDatabase(databaseName, 1).use { database ->
            insertLegacyFixture(database)
        }

        // Open exactly like the production app does: the version 1 file is upgraded by
        // the shipped migration, not by a test-only shortcut.
        val database = Room.databaseBuilder(context, RecordDatabase::class.java, databaseName)
            .addMigrations(*RecordDatabase.ALL_MIGRATIONS)
            .build()
        try {
            val clock = TimeProvider { Instant.parse("2026-10-17T02:00:00Z") }
            val ownerRepository = DefaultOwnerRepository(
                database = database,
                ownerDao = database.ownerDao(),
                accountDao = database.gameAccountDao(),
                orderDao = database.orderDao(),
                timeProvider = clock,
            )
            val accountRepository = DefaultGameAccountRepository(
                database = database,
                ownerDao = database.ownerDao(),
                accountDao = database.gameAccountDao(),
                orderDao = database.orderDao(),
                timeProvider = clock,
            )

            // Sanity: the legacy fixture really is the ambiguous case (three rows, two
            // accounts archived at the exact owner timestamp, one of them independently).
            assertNull(database.ownerDao().findById("owner-archived")?.archiveBatchId)

            ownerRepository.restoreOwner("owner-archived")

            val restoredOwner = database.ownerDao().findById("owner-archived")
            assertNull("The owner itself is restored", restoredOwner?.archivedAt)
            assertNull(restoredOwner?.archiveBatchId)
            assertTrue(
                "The restored owner appears in the active list",
                database.ownerDao().observeActive().first().any { it.id == "owner-archived" },
            )

            val detail = checkNotNull(ownerRepository.observeOwner("owner-archived").first())
            assertTrue("No account may be restored automatically", detail.activeAccounts.isEmpty())
            assertEquals(
                setOf("account-with-owner", "account-independent"),
                detail.archivedAccounts.map { it.id }.toSet(),
            )
            assertTrue(
                "Accounts whose provenance is unknown stay archived",
                detail.archivedAccounts.none { it.archivedWithOwner },
            )

            // Both remain explicitly restorable once the owner is active again.
            accountRepository.restoreAccount("account-independent")
            accountRepository.restoreAccount("account-with-owner")

            val afterManualRestore = checkNotNull(ownerRepository.observeOwner("owner-archived").first())
            assertEquals(2, afterManualRestore.activeAccounts.size)
            assertTrue(afterManualRestore.archivedAccounts.isEmpty())
            assertFalse(
                afterManualRestore.activeAccounts.any { it.archived },
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun bothSchemaVersionsAreExportableAndVersionTwoCarriesProvenanceColumns() {
        val assets = instrumentation.context.assets

        assets.open("com.kuankuan.daigan.data.local.RecordDatabase/1.json")
            .bufferedReader()
            .use { it.readText() }
            .let { versionOne ->
                assertTrue("The committed v1 schema must be preserved", versionOne.contains("\"version\": 1"))
                assertFalse(
                    "The v1 schema must stay the historical fixture",
                    versionOne.contains("archiveBatchId"),
                )
            }

        val versionTwo = assets.open("com.kuankuan.daigan.data.local.RecordDatabase/2.json")
            .bufferedReader()
            .use { it.readText() }
        assertTrue(versionTwo.contains("\"version\": 2"))
        assertTrue(versionTwo.contains("\"columnName\": \"archiveBatchId\""))
        assertTrue(versionTwo.contains("\"tableName\": \"owners\""))
        assertTrue(versionTwo.contains("\"tableName\": \"game_accounts\""))

        val versionThree = assets.open("com.kuankuan.daigan.data.local.RecordDatabase/3.json")
            .bufferedReader()
            .use { it.readText() }
        assertTrue(versionThree.contains("\"version\": 3"))
        assertTrue(versionThree.contains("\"tableName\": \"orders\""))
        assertTrue(versionThree.contains("\"tableName\": \"task_occurrences\""))
    }

    private fun insertLegacyFixture(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.execSQL(
            """
            INSERT INTO owners (id, number, nickname, createdAt, updatedAt, archivedAt)
            VALUES ('owner-active', 1, '在用老板', 1000, 1000, NULL)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO owners (id, number, nickname, createdAt, updatedAt, archivedAt)
            VALUES ('owner-archived', 2, '归档老板', 1000, 2000, 2000)
            """.trimIndent(),
        )
        database.execSQL("INSERT INTO owner_number_sequence (id, nextNumber) VALUES (0, 3)")
        database.execSQL(
            """
            INSERT INTO game_accounts
                (id, ownerId, gameName, server, uid, loginAccount, loginMethod,
                 password, createdAt, updatedAt, archivedAt)
            VALUES ('account-active', 'owner-active', '鸣潮', '官服', 'uid-active',
                    'login-active', 'PASSWORD', 'pass-active', 1000, 1000, NULL)
            """.trimIndent(),
        )
        // Archived together with the owner at 2000 ...
        database.execSQL(
            """
            INSERT INTO game_accounts
                (id, ownerId, gameName, server, uid, loginAccount, loginMethod,
                 password, createdAt, updatedAt, archivedAt)
            VALUES ('account-with-owner', 'owner-archived', '重返未来：1999', '官服',
                    'uid-with-owner', 'login-owner', 'PASSWORD', 'pass-owner', 1000, 2000, 2000)
            """.trimIndent(),
        )
        // ... and archived on its own at the very same millisecond. Version 1 cannot
        // tell these two apart, which is exactly why the batch id exists.
        database.execSQL(
            """
            INSERT INTO game_accounts
                (id, ownerId, gameName, server, uid, loginAccount, loginMethod,
                 password, createdAt, updatedAt, archivedAt)
            VALUES ('account-independent', 'owner-archived', '鸣潮', '国服',
                    'uid-independent', 'login-independent', 'VERIFICATION_CODE', NULL, 1000, 2000, 2000)
            """.trimIndent(),
        )
    }

    private fun assertOwner(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        id: String,
        number: Int,
        nickname: String,
        archivedAt: Long?,
    ) {
        database.query(
            "SELECT number, nickname, archivedAt, archiveBatchId FROM owners WHERE id = ?",
            arrayOf(id),
        ).use { cursor ->
            assertTrue("Owner $id must survive the migration", cursor.moveToFirst())
            assertEquals(number, cursor.getInt(0))
            assertEquals(nickname, cursor.getString(1))
            if (archivedAt == null) {
                assertTrue(cursor.isNull(2))
            } else {
                assertEquals(archivedAt, cursor.getLong(2))
            }
            assertTrue("Legacy provenance must stay unknown", cursor.isNull(3))
        }
    }

    private fun assertAccount(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        id: String,
        ownerId: String,
        uid: String,
        loginMethod: String,
        password: String?,
        archivedAt: Long?,
    ) {
        database.query(
            "SELECT ownerId, uid, loginMethod, password, archivedAt, archiveBatchId " +
                "FROM game_accounts WHERE id = ?",
            arrayOf(id),
        ).use { cursor ->
            assertTrue("Account $id must survive the migration", cursor.moveToFirst())
            assertEquals(ownerId, cursor.getString(0))
            assertEquals(uid, cursor.getString(1))
            assertEquals(loginMethod, cursor.getString(2))
            if (password == null) {
                assertTrue(cursor.isNull(3))
            } else {
                assertEquals(password, cursor.getString(3))
            }
            if (archivedAt == null) {
                assertTrue(cursor.isNull(4))
            } else {
                assertEquals(archivedAt, cursor.getLong(4))
            }
            assertTrue("Legacy provenance must stay unknown", cursor.isNull(5))
        }
    }
}
