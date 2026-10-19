package com.daigan.record.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.AccountDraft
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.data.model.PaymentChannel
import com.daigan.record.data.model.PaymentRequest
import com.daigan.record.data.model.TemplateDraft
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.shortDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room and repositories: the restore barrier covers each actual write entry point. */
@RunWith(AndroidJUnit4::class)
class DatasetReplacementIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var env: OrderTestEnvironment

    @Before
    fun setUp() { env = OrderTestEnvironment(context) }

    @After
    fun tearDown() { env.close() }

    @Test
    fun allRepositoryWritesWaitForExclusiveAccessThenCommit() = runBlocking {
        withTimeout(10_000) {
            val writes = representativeWrites()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                DatasetGate.withExclusiveDatasetAccess { entered.complete(Unit); release.await() }
            }
            entered.await()
            val before = businessSnapshot()
            try {
                val queued = writes.map { write -> async(start = CoroutineStart.UNDISPATCHED) { write() } }
                assertTrue(queued.all { !it.isCompleted })
                assertEquals(before, businessSnapshot())
                release.complete(Unit)
                exclusive.join()
                queued.awaitAll()
                assertEquals(2, env.database.ownerDao().findAll().size)
                assertEquals(2, env.database.orderDao().findAllOrders().size)
                assertEquals(1, env.database.receiptDao().findAll().size)
                assertEquals("屏障后账号", env.database.gameAccountDao().findAll().single().gameName)
                assertTrue(env.database.orderDao().findAllTasks().any { it.completedAt != null })
                assertTrue(env.templateRepository.findAllSummaries().any { it.gameName == "屏障后模板" })
            } finally {
                release.complete(Unit)
                exclusive.cancelAndJoin()
            }
        }
    }

    @Test
    fun queuedRepositoryWritesCannotEnterTheSuccessfullyReplacedDataset() = runBlocking {
        withTimeout(10_000) {
            val writes = representativeWrites()
            val release = CompletableDeferred<Unit>()
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                DatasetGate.withExclusiveDatasetReplacement {
                    release.await()
                    // A committed replacement may contain exactly the same business ids.
                    // Generation, rather than missing rows, must reject these old commands.
                    DatasetGate.datasetReplaced()
                }
            }
            val before = businessSnapshot()
            try {
                val queued = writes.map { write ->
                    async(start = CoroutineStart.UNDISPATCHED) { runCatching { write() } }
                }
                assertTrue(queued.all { !it.isCompleted })
                release.complete(Unit)
                exclusive.join()
                assertTrue(queued.awaitAll().all { it.isFailure })
                assertEquals(before, businessSnapshot())
                env.ownerRepository.createOwner("恢复后的新操作")
                assertEquals(2, env.database.ownerDao().findAll().size)
            } finally {
                release.complete(Unit)
                exclusive.cancelAndJoin()
            }
        }
    }

    @Test
    fun cancellingAnActiveWriterReleasesTheBarrierAndNestedRepositoryWritesWork() = runBlocking {
        withTimeout(10_000) {
            val entered = CompletableDeferred<Unit>()
            val parked = CompletableDeferred<Unit>()
            val writer = launch(start = CoroutineStart.UNDISPATCHED) {
                DatasetGate.withWrite {
                    env.ownerRepository.createOwner("取消前已完成的事务")
                    entered.complete(Unit)
                    parked.await()
                }
            }
            entered.await()
            val exclusiveEntered = CompletableDeferred<Unit>()
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                DatasetGate.withExclusiveDatasetAccess { exclusiveEntered.complete(Unit) }
            }
            assertFalse(exclusiveEntered.isCompleted)
            writer.cancelAndJoin()
            exclusive.join()
            assertTrue(exclusiveEntered.isCompleted)
            env.ownerRepository.createOwner("取消后仍可写")
            assertEquals(2, env.database.ownerDao().findAll().size)
        }
    }

    @Test
    fun ordinaryWritersRemainConcurrentWhileExclusiveWaitsForBoth() = runBlocking {
        withTimeout(10_000) {
            val release = CompletableDeferred<Unit>()
            val firstEntered = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                DatasetGate.withWrite {
                    env.ownerRepository.createOwner("并发甲")
                    firstEntered.complete(Unit)
                    release.await()
                }
            }
            firstEntered.await()
            val second = launch(start = CoroutineStart.UNDISPATCHED) {
                DatasetGate.withWrite {
                    env.ownerRepository.createOwner("并发乙")
                    secondEntered.complete(Unit)
                    release.await()
                }
            }
            secondEntered.await()
            val exclusiveEntered = CompletableDeferred<Unit>()
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                DatasetGate.withExclusiveDatasetAccess { exclusiveEntered.complete(Unit) }
            }
            assertFalse(exclusiveEntered.isCompleted)
            release.complete(Unit)
            first.join()
            second.join()
            exclusive.join()
            assertTrue(exclusiveEntered.isCompleted)
            assertEquals(setOf("并发甲", "并发乙"), env.database.ownerDao().findAll().map { it.nickname }.toSet())
        }
    }

    @Test
    fun cancellingAQueuedWriterDoesNotPreventTheNextReplacementOrWrite() = runBlocking {
        withTimeout(10_000) {
            val release = CompletableDeferred<Unit>()
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                DatasetGate.withExclusiveDatasetAccess { release.await() }
            }
            try {
                val queued = launch(start = CoroutineStart.UNDISPATCHED) { env.ownerRepository.createOwner("不应保存") }
                assertFalse(queued.isCompleted)
                queued.cancelAndJoin()
            } finally {
                release.complete(Unit)
                exclusive.join()
            }
            DatasetGate.withExclusiveDatasetAccess { assertTrue(env.database.ownerDao().findAll().isEmpty()) }
            env.ownerRepository.createOwner("继续保存")
            assertEquals(1, env.database.ownerDao().findAll().size)
        }
    }

    @Test
    fun exclusiveAccessesSerializeAndCancellationReleasesTheOwner() = runBlocking {
        withTimeout(10_000) {
            val parked = CompletableDeferred<Unit>()
            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                DatasetGate.withExclusiveDatasetAccess { parked.await() }
            }
            val secondEntered = CompletableDeferred<Unit>()
            val second = launch(start = CoroutineStart.UNDISPATCHED) {
                DatasetGate.withExclusiveDatasetReplacement { secondEntered.complete(Unit) }
            }
            assertFalse(secondEntered.isCompleted)
            first.cancelAndJoin()
            second.join()
            assertTrue(secondEntered.isCompleted)
            env.ownerRepository.createOwner("独占取消后可写")
            assertEquals(1, env.database.ownerDao().findAll().size)
        }
    }

    private suspend fun representativeWrites(): List<suspend () -> Unit> {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createShortOrder(shortDraft(owner.id, account.id))
        val taskId = env.database.taskDao().findForOrder(orderId).single().id
        env.templateRepository.ensureBuiltInTemplates()
        val template = env.templateRepository.findAllSummaries().first().let {
            env.templateRepository.findTemplate(it.id)!!
        }
        return listOf(
            { env.ownerRepository.createOwner("屏障后老板"); Unit },
            { env.accountRepository.updateAccount(account.id, AccountDraft(
                gameName = "屏障后账号", server = "官服", uid = "10000628",
                loginAccount = "login-10000628", loginMethod = LoginMethod.VERIFICATION_CODE,
            )); Unit },
            { env.templateRepository.saveTemplate(TemplateDraft.from(template).copy(gameName = "屏障后模板"), template.id); Unit },
            { env.orderRepository.createShortOrder(shortDraft(owner.id, account.id)); Unit },
            { env.orderRepository.setShortTaskCompleted(taskId, true) },
            { env.receiptRepository.recordPayment(PaymentRequest(
                orderId = orderId, periodId = null, amountCents = 10000,
                receivedAt = env.clock.now(), channel = PaymentChannel.WECHAT,
                note = "屏障后收款", operationId = "barrier-payment",
            )); Unit },
        )
    }

    private suspend fun businessSnapshot(): List<Any> = listOf(
        env.database.ownerDao().findAll(), env.database.gameAccountDao().findAll(),
        env.database.orderDao().findAllOrders(), env.database.orderDao().findAllTasks(),
        env.database.receiptDao().findAll(), env.database.templateDao().findAllTemplates(),
    )
}
