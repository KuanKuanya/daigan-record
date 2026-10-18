package com.daigan.record.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.model.AccountDraft
import com.daigan.record.data.model.LoginMethod
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Repository tests against a real Room database (file backed, so transactions,
 * uniqueness constraints and foreign keys are genuinely enforced).
 */
@RunWith(AndroidJUnit4::class)
class OwnerRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "owner-repository-test.db"
    private val clock = MutableClock(Instant.parse("2026-10-17T02:00:00Z"))

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
            orderDao = database.orderDao(),
            timeProvider = clock,
        )
        accountRepository = DefaultGameAccountRepository(
            database = database,
            ownerDao = database.ownerDao(),
            accountDao = database.gameAccountDao(),
            orderDao = database.orderDao(),
            timeProvider = clock,
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun concurrentOwnerCreationAllocatesUniqueSequentialNumbers() = runBlocking {
        val created = withContext(Dispatchers.IO) {
            (1..20).map { index ->
                async { ownerRepository.createOwner("并发老板$index") }
            }.awaitAll()
        }

        val numbers = created.map { it.number }.sorted()
        assertEquals((1..20).toList(), numbers)
        assertEquals(20, numbers.distinct().size)
        assertEquals(20, database.ownerDao().count())
        assertEquals(20, ownerRepository.observeActiveOwners().first().size)
    }

    @Test
    fun renamingAnOwnerKeepsItsNumber() = runBlocking {
        val created = ownerRepository.createOwner("小满")

        ownerRepository.renameOwner(created.id, "小满（老客）")

        val reloaded = ownerRepository.findOwner(created.id)
        assertEquals(created.number, reloaded?.number)
        assertEquals("小满（老客）", reloaded?.nickname)
    }

    @Test
    fun oneOwnerCanHoldMultipleAccounts() = runBlocking {
        val owner = ownerRepository.createOwner("小满")

        accountRepository.createAccount(owner.id, passwordDraft(gameName = "重返未来：1999", uid = "10000628"))
        accountRepository.createAccount(owner.id, passwordDraft(gameName = "鸣潮", uid = "10002136"))

        val detail = ownerRepository.observeOwner(owner.id).first()
        assertEquals(2, detail?.activeAccounts?.size)
        assertEquals(listOf("重返未来：1999", "鸣潮"), detail?.activeAccounts?.map { it.gameName })
    }

    @Test
    fun accountsAreIsolatedBetweenOwners() = runBlocking {
        val xiaoman = ownerRepository.createOwner("小满")
        val acheng = ownerRepository.createOwner("阿橙")
        accountRepository.createAccount(xiaoman.id, passwordDraft(gameName = "鸣潮", uid = "10000628"))
        accountRepository.createAccount(acheng.id, passwordDraft(gameName = "鸣潮", uid = "10001392"))

        val summaries = ownerRepository.observeActiveOwners().first()
        val xiaomanAccounts = summaries.first { it.owner.id == xiaoman.id }.accounts
        val achengAccounts = summaries.first { it.owner.id == acheng.id }.accounts

        assertEquals(1, xiaomanAccounts.size)
        assertEquals(1, achengAccounts.size)
        assertEquals(xiaoman.id, xiaomanAccounts.single().ownerId)
        assertEquals("10001392", achengAccounts.single().uid)
        assertTrue(xiaomanAccounts.none { it.id == achengAccounts.single().id })
    }

    @Test
    fun switchingToVerificationCodeLoginClearsTheStoredPassword() = runBlocking {
        val owner = ownerRepository.createOwner("小满")
        val account = accountRepository.createAccount(
            owner.id,
            passwordDraft(gameName = "鸣潮", uid = "10002136", password = "demo1999"),
        )
        assertEquals("demo1999", database.gameAccountDao().findById(account.id)?.password)

        accountRepository.updateAccount(
            account.id,
            AccountDraft(
                gameName = "鸣潮",
                server = "国服",
                uid = "10002136",
                loginAccount = "demo-acheng",
                loginMethod = LoginMethod.VERIFICATION_CODE,
            ),
        )

        val stored = database.gameAccountDao().findById(account.id)
        assertEquals(LoginMethod.VERIFICATION_CODE, stored?.loginMethod)
        assertNull("Switching to code login must delete the stored password", stored?.password)
    }

    @Test
    fun archivingAnOwnerArchivesItsAccountsAndRestoreBringsThemBack() = runBlocking {
        val owner = ownerRepository.createOwner("小满")
        accountRepository.createAccount(owner.id, passwordDraft())

        clock.advance()
        ownerRepository.archiveOwner(owner.id)

        assertTrue(ownerRepository.observeActiveOwners().first().isEmpty())
        val archivedOwner = ownerRepository.observeArchivedOwners().first().single()
        assertEquals(owner.id, archivedOwner.owner.id)
        assertEquals(1, archivedOwner.accounts.size)
        assertEquals(0, database.gameAccountDao().observeActive().first().size)

        clock.advance()
        ownerRepository.restoreOwner(owner.id)

        val restored = ownerRepository.observeActiveOwners().first().single()
        assertEquals(owner.id, restored.owner.id)
        assertEquals(1, restored.activeAccountCount)
        assertEquals(1, database.gameAccountDao().observeActive().first().size)
    }

    @Test
    fun singleAccountArchiveAndRestoreKeepItOutOfTheOwnerDetail() = runBlocking {
        val owner = ownerRepository.createOwner("小满")
        val account = accountRepository.createAccount(owner.id, passwordDraft())

        accountRepository.archiveAccount(account.id)

        val afterArchive = ownerRepository.observeOwner(owner.id).first()
        assertEquals(0, afterArchive?.activeAccounts?.size)
        assertEquals(1, afterArchive?.archivedAccounts?.size)
        assertEquals(1, accountRepository.observeArchivedAccounts().first().size)

        accountRepository.restoreAccount(account.id)

        val afterRestore = ownerRepository.observeOwner(owner.id).first()
        assertEquals(1, afterRestore?.activeAccounts?.size)
        assertEquals(0, afterRestore?.archivedAccounts?.size)
    }

    @Test
    fun accountRequiresAnExistingOwner() = runBlocking {
        val result = runCatching {
            accountRepository.createAccount("missing-owner", passwordDraft())
        }

        assertTrue(result.isFailure)
        assertTrue(database.gameAccountDao().observeActive().first().isEmpty())
    }

    @Test
    fun ownerNumbersAreNotReusedAfterArchiving() = runBlocking {
        val first = ownerRepository.createOwner("小满")
        clock.advance()
        ownerRepository.archiveOwner(first.id)

        val second = ownerRepository.createOwner("阿橙")

        assertEquals(first.number + 1, second.number)
    }

    @Test
    fun ownerNumberIsPersistedWithTheOwnerRow() = runBlocking {
        val created = ownerRepository.createOwner("北辰")

        val row = database.ownerDao().findById(created.id)

        assertEquals(1, row?.number)
        assertEquals("B001", created.displayNumber)
    }

    private fun passwordDraft(
        gameName: String = "重返未来：1999",
        uid: String = "10000628",
        password: String = "demo1999",
    ) = AccountDraft(
        gameName = gameName,
        server = "官服",
        uid = uid,
        loginAccount = "demo-login",
        loginMethod = LoginMethod.PASSWORD,
        password = password,
    )
}

/** Deterministic clock so archive timestamps are distinguishable inside one test. */
private class MutableClock(private var current: Instant) : TimeProvider {
    override fun now(): Instant = current

    fun advance(seconds: Long = 60) {
        current = current.plusSeconds(seconds)
    }
}
