package com.daigan.record.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.local.entity.GameAccountEntity
import com.daigan.record.data.local.entity.OwnerEntity
import com.daigan.record.data.model.LoginMethod
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Room/SQLite tests for database invariants. No fakes are involved.
 */
@RunWith(AndroidJUnit4::class)
class RecordDatabaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "record-database-test.db"
    private lateinit var database: RecordDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(databaseName)
        database = open()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    private fun open(): RecordDatabase =
        Room.databaseBuilder(context, RecordDatabase::class.java, databaseName).build()

    @Test
    fun foreignKeyRejectsAccountWithoutOwner() = runBlocking {
        val result = runCatching {
            database.gameAccountDao().insert(account(ownerId = "missing-owner"))
        }

        assertTrue(
            "Expected a SQLite foreign key violation, got $result",
            result.exceptionOrNull() is SQLiteConstraintException,
        )
    }

    @Test
    fun uniqueIndexRejectsDuplicateOwnerNumber() = runBlocking {
        database.ownerDao().insert(ownerRow(id = "owner-1", number = 7))

        val result = runCatching {
            database.ownerDao().insert(ownerRow(id = "owner-2", number = 7))
        }

        assertTrue(
            "Expected a unique index violation, got $result",
            result.exceptionOrNull() is SQLiteConstraintException,
        )
    }

    @Test
    fun deletingAnOwnerWithAccountsIsRejected() = runBlocking {
        database.ownerDao().insert(ownerRow())
        database.gameAccountDao().insert(account())

        val result = runCatching {
            database.openHelper.writableDatabase.execSQL("DELETE FROM owners WHERE id = 'owner-1'")
        }

        assertTrue(
            "Expected RESTRICT to block the delete, got $result",
            result.exceptionOrNull() is SQLiteConstraintException,
        )
        assertEquals(1, database.ownerDao().count())
    }

    @Test
    fun recordsSurviveDatabaseCloseAndReopen() = runBlocking {
        database.ownerDao().insert(ownerRow(nickname = "小满"))
        database.gameAccountDao().insert(account())
        database.close()

        database = open()

        val owners = database.ownerDao().observeActive().first()
        val accounts = database.gameAccountDao().observeActive().first()
        assertEquals(1, owners.size)
        assertEquals("小满", owners.single().nickname)
        assertEquals(1, accounts.size)
        assertEquals("10000628", accounts.single().uid)
    }

    @Test
    fun archiveQueriesSeparateActiveAndArchivedOwners() = runBlocking {
        database.ownerDao().insert(ownerRow(id = "owner-1", number = 1))
        database.ownerDao().insert(
            ownerRow(id = "owner-2", number = 2, archivedAt = Instant.ofEpochMilli(2_000)),
        )

        val active = database.ownerDao().observeActive().first()
        val archived = database.ownerDao().observeArchived().first()

        assertEquals(listOf("owner-1"), active.map { it.id })
        assertEquals(listOf("owner-2"), archived.map { it.id })
    }

    @Test
    fun accountNumbersAndOwnersStayIndependent() = runBlocking {
        database.ownerDao().insert(ownerRow(id = "owner-1", number = 1))
        database.ownerDao().insert(ownerRow(id = "owner-2", number = 2))
        database.gameAccountDao().insert(account(id = "account-1", ownerId = "owner-1"))
        database.gameAccountDao().insert(account(id = "account-2", ownerId = "owner-2"))

        val ownerOneAccounts = database.gameAccountDao().observeForOwner("owner-1").first()

        assertEquals(listOf("account-1"), ownerOneAccounts.map { it.id })
        assertEquals(1, database.gameAccountDao().countForOwner("owner-1"))
        assertEquals(1, database.gameAccountDao().countForOwner("owner-2"))
    }

    @Test
    fun sequenceStartsAtOneAndIncrements() = runBlocking {
        database.ownerDao().ensureNumberSequence()
        database.ownerDao().incrementNumberSequence()
        assertEquals(1, database.ownerDao().lastAllocatedNumber())
        database.ownerDao().incrementNumberSequence()
        assertEquals(2, database.ownerDao().lastAllocatedNumber())
    }

    private fun ownerRow(
        id: String = "owner-1",
        number: Int = 1,
        nickname: String = "小满",
        archivedAt: Instant? = null,
    ) = OwnerEntity(
        id = id,
        number = number,
        nickname = nickname,
        createdAt = Instant.ofEpochMilli(1_000),
        updatedAt = Instant.ofEpochMilli(1_000),
        archivedAt = archivedAt,
    )

    private fun account(
        id: String = "account-1",
        ownerId: String = "owner-1",
    ) = GameAccountEntity(
        id = id,
        ownerId = ownerId,
        gameName = "重返未来：1999",
        server = "官服",
        uid = "10000628",
        loginAccount = "demo-xiaoman",
        loginMethod = LoginMethod.PASSWORD,
        password = "demo1999",
        createdAt = Instant.ofEpochMilli(1_000),
        updatedAt = Instant.ofEpochMilli(1_000),
        archivedAt = null,
    )
}
