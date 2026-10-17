package com.daigan.record.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.local.entity.GameAccountEntity
import com.daigan.record.data.local.entity.OwnerEntity
import com.daigan.record.data.model.AccountDraft
import com.daigan.record.data.model.LoginMethod
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for archive ownership and parent/child archive consistency.
 *
 * Every test runs against a real Room database (file backed) so the transaction
 * boundaries, foreign keys and the persisted archive provenance are genuinely
 * exercised. The clock is controllable because two operations are deliberately
 * performed at the *same* millisecond: a correct implementation must not rely on
 * timestamp equality to decide what belongs to an owner archive.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveOwnershipRegressionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "archive-ownership-regression-test.db"
    private val clock = ControllableClock(Instant.parse("2026-10-17T02:00:00Z"))

    private lateinit var database: RecordDatabase
    private lateinit var ownerRepository: DefaultOwnerRepository
    private lateinit var accountRepository: DefaultGameAccountRepository

    @Before
    fun setUp() {
        context.deleteDatabase(databaseName)
        database = Room.databaseBuilder(context, RecordDatabase::class.java, databaseName).build()
        ownerRepository = DefaultOwnerRepository(
            database = database,
            ownerDao = database.ownerDao(),
            accountDao = database.gameAccountDao(),
            timeProvider = clock,
        )
        accountRepository = DefaultGameAccountRepository(
            database = database,
            ownerDao = database.ownerDao(),
            accountDao = database.gameAccountDao(),
            timeProvider = clock,
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    /**
     * The reproduction from the review: an account archived on its own at instant T,
     * then the owner archived with another account at the exact same instant T.
     * Restoring the owner must leave the independently archived account archived.
     */
    @Test
    fun independentArchiveAtTheSameInstantIsNotOwnedByTheOwnerArchive() = runBlocking {
        val owner = ownerRepository.createOwner("小满")
        val independent = accountRepository.createAccount(
            owner.id,
            draft(gameName = "独立归档", uid = "uid-independent"),
        )
        val withOwner = accountRepository.createAccount(
            owner.id,
            draft(gameName = "随老板归档", uid = "uid-with-owner"),
        )

        // Both writes observe the same millisecond on purpose.
        clock.freezeAt(Instant.parse("2026-10-17T03:00:00Z"))
        accountRepository.archiveAccount(independent.id)
        ownerRepository.archiveOwner(owner.id)

        // Provenance is persisted explicitly instead of being inferred from the clock.
        val ownerBatchId = ownerRow(owner.id).archiveBatchId
        assertNotNull("The owner archive must record a batch", ownerBatchId)
        assertEquals(
            "The account archived with the owner joins the owner batch",
            ownerBatchId,
            accountRow(withOwner.id).archiveBatchId,
        )
        assertNull(
            "An independently archived account must not be claimed by the owner batch",
            accountRow(independent.id).archiveBatchId,
        )
        assertEquals(
            "Only the account of the owner batch is counted as archived with the owner",
            1,
            ownerRepository.observeArchivedOwners().first().single().accountsArchivedWithOwnerCount,
        )

        clock.advance()
        ownerRepository.restoreOwner(owner.id)

        val detail = checkNotNull(ownerRepository.observeOwner(owner.id).first())
        assertEquals(
            "Only the account archived together with the owner may come back",
            listOf(withOwner.id),
            detail.activeAccounts.map { it.id },
        )
        assertEquals(
            "The independently archived account must stay archived",
            listOf(independent.id),
            detail.archivedAccounts.map { it.id },
        )
        assertTrue(accountRepository.findAccount(independent.id)?.archived == true)
        assertFalse(accountRepository.findAccount(withOwner.id)?.archived == true)
    }

    /** The archived-account view must keep listing the independently archived account. */
    @Test
    fun ownerRestoreKeepsIndependentlyArchivedAccountInTheArchivedList() = runBlocking {
        val owner = ownerRepository.createOwner("小满")
        val independent = accountRepository.createAccount(owner.id, draft(uid = "uid-independent"))
        val withOwner = accountRepository.createAccount(owner.id, draft(uid = "uid-with-owner"))

        clock.freezeAt(Instant.parse("2026-10-17T03:00:00Z"))
        accountRepository.archiveAccount(independent.id)
        ownerRepository.archiveOwner(owner.id)
        assertTrue(
            "Accounts of an archived owner are listed under that owner, not separately",
            accountRepository.observeArchivedAccounts().first().isEmpty(),
        )

        clock.advance()
        ownerRepository.restoreOwner(owner.id)

        val archived = accountRepository.observeArchivedAccounts().first()
        assertEquals(listOf(independent.id), archived.map { it.account.id })
        assertEquals(owner.id, archived.single().ownerId)

        // ... and it can still be restored explicitly once the owner is active again.
        accountRepository.restoreAccount(independent.id)
        assertFalse(accountRepository.findAccount(independent.id)?.archived == true)
        assertFalse(accountRepository.findAccount(withOwner.id)?.archived == true)
    }

    /** Repeated requests are idempotent and never re-stamp the stored provenance. */
    @Test
    fun repeatedArchiveRequestsKeepTheOwnerArchiveProvenanceStable() = runBlocking {
        val owner = ownerRepository.createOwner("小满")
        val account = accountRepository.createAccount(owner.id, draft())

        clock.freezeAt(Instant.parse("2026-10-17T03:00:00Z"))
        ownerRepository.archiveOwner(owner.id)
        val ownerAfterFirstArchive = ownerRow(owner.id)
        val accountAfterFirstArchive = accountRow(account.id)
        assertNotNull(ownerAfterFirstArchive.archiveBatchId)

        // A later clock value must not re-stamp anything: the archive happened once.
        clock.advance(seconds = 3_600)
        ownerRepository.archiveOwner(owner.id)

        assertEquals(
            "A repeated archive must not overwrite the stored provenance",
            ownerAfterFirstArchive,
            ownerRow(owner.id),
        )
        assertEquals(accountAfterFirstArchive, accountRow(account.id))

        ownerRepository.restoreOwner(owner.id)
        assertEquals(null, ownerRow(owner.id).archivedAt)
        assertFalse(accountRow(account.id).archivedAt != null)

        // Restoring an already active owner is a no-op, not an error and not a change.
        val activeOwnerRow = ownerRow(owner.id)
        ownerRepository.restoreOwner(owner.id)
        assertEquals(activeOwnerRow, ownerRow(owner.id))
    }

    /** Repeated account archive/restore requests behave the same way. */
    @Test
    fun repeatedAccountArchiveAndRestoreRequestsAreIdempotent() = runBlocking {
        val owner = ownerRepository.createOwner("小满")
        val account = accountRepository.createAccount(owner.id, draft())

        clock.freezeAt(Instant.parse("2026-10-17T03:00:00Z"))
        accountRepository.archiveAccount(account.id)
        val archivedRow = accountRow(account.id)

        clock.advance(seconds = 3_600)
        accountRepository.archiveAccount(account.id)
        assertEquals(archivedRow, accountRow(account.id))

        accountRepository.restoreAccount(account.id)
        val activeRow = accountRow(account.id)
        assertTrue(activeRow.archivedAt == null)

        accountRepository.restoreAccount(account.id)
        assertEquals(activeRow, accountRow(account.id))
    }

    /** Several owner archive/restore cycles keep the independent archive intact. */
    @Test
    fun multipleOwnerArchiveRestoreCyclesPreserveIndependentArchives() = runBlocking {
        val owner = ownerRepository.createOwner("小满")
        val independent = accountRepository.createAccount(owner.id, draft(uid = "uid-independent"))
        val withOwner = accountRepository.createAccount(owner.id, draft(uid = "uid-with-owner"))

        accountRepository.archiveAccount(independent.id)

        val clockValues = listOf(
            Instant.parse("2026-10-17T03:00:00Z"),
            Instant.parse("2026-10-17T04:00:00Z"),
            Instant.parse("2026-10-17T05:00:00Z"),
        )
        for (instant in clockValues) {
            clock.freezeAt(instant)
            ownerRepository.archiveOwner(owner.id)
            clock.advance()

            val archivedDetail = ownerRepository.observeOwner(owner.id).first()
            assertTrue(archivedDetail?.activeAccounts?.isEmpty() == true)
            assertEquals(2, archivedDetail?.archivedAccounts?.size)

            ownerRepository.restoreOwner(owner.id)

            val detail = ownerRepository.observeOwner(owner.id).first()
            assertEquals(
                "Every cycle restores exactly the accounts archived with the owner",
                listOf(withOwner.id),
                detail?.activeAccounts?.map { it.id },
            )
            assertEquals(
                "The independently archived account survives every cycle",
                listOf(independent.id),
                detail?.archivedAccounts?.map { it.id },
            )
        }

        // Explicit restoration still works after the cycles.
        accountRepository.restoreAccount(independent.id)
        assertEquals(
            setOf(independent.id, withOwner.id),
            ownerRepository.observeOwner(owner.id).first()?.activeAccounts?.map { it.id }?.toSet(),
        )
    }

    /**
     * While the owner is archived every account write is rejected inside the write
     * transaction, and the stored rows are untouched (no partial writes).
     */
    @Test
    fun accountWritesAreRejectedWhileTheOwnerIsArchivedWithoutChangingStoredData() = runBlocking {
        val owner = ownerRepository.createOwner("小满")
        val first = accountRepository.createAccount(owner.id, draft(gameName = "鸣潮", uid = "uid-1"))
        val second = accountRepository.createAccount(owner.id, draft(gameName = "鸣潮", uid = "uid-2"))

        clock.freezeAt(Instant.parse("2026-10-17T03:00:00Z"))
        ownerRepository.archiveOwner(owner.id)

        val ownerBefore = ownerRow(owner.id)
        val firstBefore = accountRow(first.id)
        val secondBefore = accountRow(second.id)
        val countBefore = database.gameAccountDao().countForOwner(owner.id)

        assertRejected { accountRepository.createAccount(owner.id, draft(gameName = "新账号", uid = "uid-3")) }
        assertRejected {
            accountRepository.updateAccount(first.id, draft(gameName = "改名后的账号", uid = "uid-1"))
        }
        assertRejected { accountRepository.archiveAccount(second.id) }
        assertRejected { accountRepository.restoreAccount(second.id) }

        assertEquals("No account may be created while the owner is archived", countBefore, database.gameAccountDao().countForOwner(owner.id))
        assertEquals(ownerBefore, ownerRow(owner.id))
        assertEquals(firstBefore, accountRow(first.id))
        assertEquals(secondBefore, accountRow(second.id))
    }

    /** Creating an account under an archived owner is rejected by the repository. */
    @Test
    fun creatingAnAccountUnderAnArchivedOwnerIsRejected() = runBlocking {
        val owner = ownerRepository.createOwner("小满")
        ownerRepository.archiveOwner(owner.id)

        val failure = assertRejected {
            accountRepository.createAccount(owner.id, draft())
        }

        assertNotNull(failure)
        assertEquals(0, database.gameAccountDao().countForOwner(owner.id))
    }

    private suspend fun assertRejected(block: suspend () -> Unit): Throwable {
        val result = runCatching { block() }
        assertTrue("Expected the operation to be rejected, but it succeeded", result.isFailure)
        return checkNotNull(result.exceptionOrNull())
    }

    private suspend fun ownerRow(ownerId: String): OwnerEntity =
        checkNotNull(database.ownerDao().findById(ownerId))

    private suspend fun accountRow(accountId: String): GameAccountEntity =
        checkNotNull(database.gameAccountDao().findById(accountId))

    private fun draft(
        gameName: String = "重返未来：1999",
        uid: String = "10000628",
    ) = AccountDraft(
        gameName = gameName,
        server = "官服",
        uid = uid,
        loginAccount = "demo-login",
        loginMethod = LoginMethod.PASSWORD,
        password = "demo1999",
    )
}

/** A clock the tests can stop, advance or jump; millisecond equality is intentional. */
private class ControllableClock(private var current: Instant) : TimeProvider {
    override fun now(): Instant = current

    fun freezeAt(instant: Instant) {
        current = instant
    }

    fun advance(seconds: Long = 60) {
        current = current.plusSeconds(seconds)
    }
}
