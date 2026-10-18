package com.daigan.record.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.ProgressType
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.hostingDraft
import com.daigan.record.testing.numericTask
import com.daigan.record.testing.shortDraft
import java.io.File
import java.time.Instant
import java.time.LocalDate
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

@RunWith(AndroidJUnit4::class)
class OrderRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var env: OrderTestEnvironment

    @Before
    fun setUp() {
        File(context.filesDir, "attachments").deleteRecursively()
        env = OrderTestEnvironment(context)
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun oneAccountCanHaveShortAndHostingOrdersAtTheSameTime() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val today = LocalDate.of(2026, 10, 17)
        val shortId = env.orderRepository.createShortOrder(
            shortDraft(owner.id, account.id, deadline = today.plusDays(3)),
        )
        val hostingId = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, today.minusDays(2), today.plusDays(5)),
        )

        val summaries = env.orderRepository.observeOrdersForOwner(owner.id).first()
        assertEquals(2, summaries.size)
        assertEquals(setOf(shortId, hostingId), summaries.map { it.order.id }.toSet())
        assertTrue(summaries.all { it.accountId == account.id })
        assertEquals(2, env.database.orderDao().countForAccount(account.id))
        assertEquals(OrderType.SHORT_TASK, env.orderRepository.observeOrderDetail(shortId).first()!!.order.type)
        assertEquals(OrderType.HOSTING, env.orderRepository.observeOrderDetail(hostingId).first()!!.order.type)
    }

    @Test
    fun ordersAreIsolatedBetweenOwnersAndAccounts() = runBlocking {
        val (ownerA, accountA) = env.seedOwnerAndAccount("老板甲", uid = "A-1")
        val (ownerB, accountB) = env.seedOwnerAndAccount("老板乙", uid = "B-1")
        val today = LocalDate.of(2026, 10, 17)
        env.orderRepository.createShortOrder(shortDraft(ownerA.id, accountA.id))
        env.orderRepository.createShortOrder(shortDraft(ownerB.id, accountB.id))

        val forA = env.orderRepository.observeOrdersForOwner(ownerA.id).first()
        val forB = env.orderRepository.observeOrdersForOwner(ownerB.id).first()
        assertEquals(1, forA.size)
        assertEquals(1, forB.size)
        assertEquals(accountA.id, forA.single().accountId)
        assertEquals(accountB.id, forB.single().accountId)

        // A stale / forged form cannot attach account B's order to owner A.
        val failure = runCatching {
            env.orderRepository.createHostingOrder(
                hostingDraft(ownerA.id, accountB.id, today, today.plusDays(5)),
            )
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("不属于"))
        assertEquals(1, env.database.orderDao().countForAccount(accountB.id))
    }

    @Test
    fun archivedOwnerOrAccountRejectsOrderCreationWithoutPartialRows() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val today = LocalDate.of(2026, 10, 17)
        env.accountRepository.archiveAccount(account.id)

        val accountFailure = runCatching {
            env.orderRepository.createShortOrder(shortDraft(owner.id, account.id))
        }.exceptionOrNull()
        assertNotNull(accountFailure)
        assertEquals(0, env.database.orderDao().countForAccount(account.id))

        env.accountRepository.restoreAccount(account.id)
        env.ownerRepository.archiveOwner(owner.id)
        val ownerFailure = runCatching {
            env.orderRepository.createHostingOrder(
                hostingDraft(owner.id, account.id, today, today.plusDays(3)),
            )
        }.exceptionOrNull()
        assertNotNull(ownerFailure)
        assertEquals(ARCHIVED_OWNER_ORDER_MESSAGE, ownerFailure!!.message)
        assertEquals(0, env.database.orderDao().countForAccount(account.id))
    }

    @Test
    fun shortOrderCompletionAndReopeningAreIndependentOfPayment() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createShortOrder(
            shortDraft(
                owner.id,
                account.id,
                priceYuan = "100.00",
                tasks = listOf(
                    com.daigan.record.data.model.TaskDraft(title = "签到"),
                    numericTask(title = "累计抽数"),
                ),
            ),
        )
        var detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(10000L, detail.order.priceCents)
        val checkbox = detail.tasks.first { it.progressType == ProgressType.CHECKBOX }
        val numeric = detail.tasks.first { it.progressType == ProgressType.NUMERIC }

        env.orderRepository.setShortTaskCompleted(checkbox.id, true)
        detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertNull("One task is still incomplete", detail.order.completedAt)

        env.orderRepository.updateShortTaskProgress(numeric.id, java.math.BigDecimal("50"))
        detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertNull(detail.order.completedAt)
        assertEquals(0, java.math.BigDecimal("50").compareTo(detail.tasks.first { it.id == numeric.id }.currentValue))

        env.orderRepository.updateShortTaskProgress(numeric.id, java.math.BigDecimal("100"))
        detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertNotNull("All tasks complete -> order complete", detail.order.completedAt)

        env.orderRepository.setShortTaskCompleted(checkbox.id, false)
        detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertNull("Reopening a task returns the order to unfinished", detail.order.completedAt)
        assertTrue(detail.order.priceCents == 10000L)
    }

    @Test
    fun archivingIsBlockedByUnfinishedShortOrderAndThenAllowed() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createShortOrder(
            shortDraft(owner.id, account.id, tasks = listOf(com.daigan.record.data.model.TaskDraft(title = "主线"))),
        )
        val failure = runCatching { env.ownerRepository.archiveOwner(owner.id) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(ARCHIVED_OWNER_HAS_ACTIVE_WORK_MESSAGE, failure!!.message)
        assertNull(env.database.ownerDao().findById(owner.id)!!.archivedAt)

        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        env.orderRepository.setShortTaskCompleted(detail.tasks.single().id, true)
        env.ownerRepository.archiveOwner(owner.id)

        val ownerRow = env.database.ownerDao().findById(owner.id)!!
        val accountRow = env.database.gameAccountDao().findById(account.id)!!
        assertNotNull(ownerRow.archivedAt)
        assertNotNull(ownerRow.archiveBatchId)
        assertEquals(ownerRow.archiveBatchId, accountRow.archiveBatchId)

        // Archived records stay readable, including the completed order.
        val archivedDetail = env.orderRepository.observeOrderDetail(orderId).first()
        assertNotNull(archivedDetail)
        assertEquals(1, archivedDetail!!.tasks.size)
        assertEquals(1, env.orderRepository.observeOrdersForOwner(owner.id).first().size)
    }

    @Test
    fun archivingIsBlockedByUnexpiredHostingPeriodAndWorksAfterExpiry() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val today = LocalDate.of(2026, 10, 17)
        env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, today.minusDays(2), today.plusDays(2)),
        )

        val failure = runCatching { env.ownerRepository.archiveOwner(owner.id) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(ARCHIVED_OWNER_HAS_ACTIVE_WORK_MESSAGE, failure!!.message)
        assertNull(env.database.ownerDao().findById(owner.id)!!.archivedAt)

        // Move the injected clock past the inclusive end date without changing device time.
        env.clock.set(Instant.parse("2026-10-25T02:00:00Z"))
        env.ownerRepository.archiveOwner(owner.id)
        assertNotNull(env.database.ownerDao().findById(owner.id)!!.archivedAt)
        assertTrue(
            "Expired/archived orders stop producing work",
            env.orderRepository.observeTodayHosting().first().isEmpty(),
        )
    }

    @Test
    fun accountArchiveIsBlockedByItsOwnUnexpiredPeriod() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val today = LocalDate.of(2026, 10, 17)
        env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, today, today.plusDays(5)),
        )
        val failure = runCatching { env.accountRepository.archiveAccount(account.id) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(ARCHIVED_ACCOUNT_HAS_ACTIVE_WORK_MESSAGE, failure!!.message)
        assertNull(env.database.gameAccountDao().findById(account.id)!!.archivedAt)
    }

    @Test
    fun shortOrdersSortOverdueFirstThenDeadlineThenNoDeadline() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val today = LocalDate.of(2026, 10, 17)
        val noDeadline = env.orderRepository.createShortOrder(
            shortDraft(owner.id, account.id, deadline = null, title = "无期限"),
        )
        val later = env.orderRepository.createShortOrder(
            shortDraft(owner.id, account.id, deadline = today.plusDays(4), title = "较晚"),
        )
        val overdue = env.orderRepository.createShortOrder(
            shortDraft(owner.id, account.id, deadline = today.minusDays(2), title = "逾期"),
        )
        val soon = env.orderRepository.createShortOrder(
            shortDraft(owner.id, account.id, deadline = today.plusDays(2), title = "稍后"),
        )
        val ordered = env.orderRepository.observeTodayShort().first().map { it.order.id }
        assertEquals(listOf(overdue, soon, later, noDeadline), ordered)
        assertTrue(env.orderRepository.observeTodayShort().first().first().overdue)
    }

    @Test
    fun undoIsRejectedWhileTheOwnerIsArchived() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createShortOrder(
            shortDraft(
                owner.id,
                account.id,
                tasks = listOf(
                    com.daigan.record.data.model.TaskDraft(title = "A"),
                    com.daigan.record.data.model.TaskDraft(title = "B"),
                ),
            ),
        )
        val batch = env.orderRepository.completeShortOrder(orderId, "undo-archived")
        env.ownerRepository.archiveOwner(owner.id) // allowed: the short order is complete

        val failure = runCatching { env.orderRepository.undoBatch(batch.batchId) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(ARCHIVED_OWNER_WORK_MESSAGE, failure!!.message)
        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertNotNull("No partial restore may happen", detail.order.completedAt)
        assertTrue(detail.tasks.all { it.completedAt != null })
        assertNull(env.database.completionBatchDao().findById(batch.batchId)!!.undoneAt)
    }

    @Test
    fun orderCreationRejectsInvalidDraftsBeforeWriting() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val today = LocalDate.of(2026, 10, 17)
        val badPrice = runCatching {
            env.orderRepository.createShortOrder(shortDraft(owner.id, account.id, priceYuan = "12.345"))
        }.exceptionOrNull()
        assertNotNull(badPrice)
        assertEquals(0, env.database.orderDao().countForAccount(account.id))

        val badDates = runCatching {
            env.orderRepository.createHostingOrder(
                hostingDraft(owner.id, account.id, today, today.minusDays(1)),
            )
        }.exceptionOrNull()
        assertNotNull(badDates)
        assertEquals(0, env.database.orderDao().countForAccount(account.id))
        assertFalse(env.database.orderDao().findForAccount(account.id).isNotEmpty())
    }
}
