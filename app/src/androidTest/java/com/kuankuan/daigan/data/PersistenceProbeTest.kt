package com.kuankuan.daigan.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kuankuan.daigan.core.time.TimeProvider
import com.kuankuan.daigan.data.local.RecordDatabase
import com.kuankuan.daigan.data.model.AccountDraft
import com.kuankuan.daigan.data.model.LoginMethod
import com.kuankuan.daigan.data.repository.DefaultGameAccountRepository
import com.kuankuan.daigan.data.repository.DefaultOwnerRepository
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Two-step probe that writes to the real app database file (the same database the
 * installed app uses) and reads it back.
 *
 * Run the methods in separate instrumentation invocations to prove survival across a
 * real process restart:
 *
 *   adb shell am instrument -w -e class com.kuankuan.daigan.data.PersistenceProbeTest#aSeedData \
 *       com.kuankuan.daigan.test/com.kuankuan.daigan.HiltTestRunner
 *   adb shell am instrument -w -e class com.kuankuan.daigan.data.PersistenceProbeTest#bVerifyDataPersisted \
 *       com.kuankuan.daigan.test/com.kuankuan.daigan.HiltTestRunner
 *
 * The class is also ordered so a normal `connectedDebugAndroidTest` run stays green.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PersistenceProbeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val probeNickname = "持久化探针老板"
    private val probeUid = "probe-uid-0001"

    private fun openDatabase(): RecordDatabase =
        Room.databaseBuilder(context, RecordDatabase::class.java, RecordDatabase.NAME)
            // The probe opens the real app database, which may still be at an older
            // version on the device, so it must run the shipped migrations too.
            .addMigrations(*RecordDatabase.ALL_MIGRATIONS)
            .build()

    @Test
    fun aSeedData() = runBlocking {
        val database = openDatabase()
        val time = TimeProvider { Instant.parse("2026-10-17T02:00:00Z") }
        val ownerRepository = DefaultOwnerRepository(
            database = database,
            ownerDao = database.ownerDao(),
            accountDao = database.gameAccountDao(),
            orderDao = database.orderDao(),
            timeProvider = time,
        )
        val accountRepository = DefaultGameAccountRepository(
            database = database,
            ownerDao = database.ownerDao(),
            accountDao = database.gameAccountDao(),
            orderDao = database.orderDao(),
            timeProvider = time,
        )

        val existing = ownerRepository.observeActiveOwners().first()
            .firstOrNull { it.owner.nickname == probeNickname }
        val owner = existing?.owner ?: ownerRepository.createOwner(probeNickname)
        if (existing == null || existing.accounts.isEmpty()) {
            accountRepository.createAccount(
                owner.id,
                AccountDraft(
                    gameName = "探针游戏",
                    server = "官服",
                    uid = probeUid,
                    loginAccount = "probe-login",
                    loginMethod = LoginMethod.VERIFICATION_CODE,
                ),
            )
        }
        database.close()

        assertTrue(owner.number > 0)
    }

    @Test
    fun bVerifyDataPersisted() = runBlocking {
        val database = openDatabase()
        val owners = database.ownerDao().observeActive().first()
        val accounts = database.gameAccountDao().observeActive().first()
        database.close()

        val probeOwner = owners.firstOrNull { it.nickname == probeNickname }
        assertTrue(
            "Owner written by the previous process must still exist after reopening",
            probeOwner != null,
        )
        val probeAccount = accounts.firstOrNull { it.uid == probeUid }
        assertTrue(
            "Account written by the previous process must still exist after reopening",
            probeAccount != null,
        )
        assertEquals(LoginMethod.VERIFICATION_CODE, probeAccount?.loginMethod)
        assertNull("Verification-code accounts never store a password", probeAccount?.password)
    }
}
