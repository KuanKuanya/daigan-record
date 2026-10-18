package com.daigan.record.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.core.time.TimeTicker
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.model.AccountDraft
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.data.repository.DefaultAttachmentRepository
import com.daigan.record.data.repository.DefaultGameAccountRepository
import com.daigan.record.data.repository.DefaultOrderRepository
import com.daigan.record.data.repository.DefaultOwnerRepository
import com.daigan.record.testing.ManualBoundaryWaiter
import com.daigan.record.testing.MutableClock
import com.daigan.record.testing.hostingDraft
import com.daigan.record.testing.writeTestJpeg
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Manual-verification fixture for expiry/renewal on the emulator.
 *
 * The app itself keeps using the real system clock. This probe writes one order with
 * an already expired period (2026-10-01..2026-10-10) into the real app database using
 * an injected clock, so the renewal screen can be exercised without changing the
 * device clock and without shipping fake production data.
 *
 * Run manually with:
 *   adb shell am instrument -w \
 *     -e class com.daigan.record.data.Phase2ManualProbeTest \
 *     com.daigan.record.test/com.daigan.record.HiltTestRunner
 */
@RunWith(AndroidJUnit4::class)
class Phase2ManualProbeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nickname = "续期探针"
    private val uid = "probe-hosting-0001"

    @Test
    fun seedExpiredHostingOrderForManualRenewal() = runBlocking {
        val database = Room.databaseBuilder(
            context,
            RecordDatabase::class.java,
            RecordDatabase.NAME,
        ).addMigrations(*RecordDatabase.ALL_MIGRATIONS).build()

        try {
            // A frozen historical clock must never be paired with real-world scheduling:
            // the real waiter would see the boundary as already past, never suspend, and
            // spin the ticker flow while the clock could not advance. The deterministic
            // waiter parks instead, and every `first()` below cancels it promptly.
            val clock = MutableClock(Instant.parse("2026-10-05T02:00:00Z"))
            val fixed = TimeProvider { clock.now() }
            val ticker = TimeTicker(fixed, ManualBoundaryWaiter(clock))
            val ownerRepository = DefaultOwnerRepository(
                database, database.ownerDao(), database.gameAccountDao(),
                database.orderDao(), fixed,
            )
            val accountRepository = DefaultGameAccountRepository(
                database, database.ownerDao(), database.gameAccountDao(),
                database.orderDao(), fixed,
            )
            val receiptLedger = com.daigan.record.data.repository.ReceiptLedger(
                receiptDao = database.receiptDao(),
                orderDao = database.orderDao(),
                timeProvider = fixed,
            )
            val orderRepository = DefaultOrderRepository(
                database = database,
                orderDao = database.orderDao(),
                taskDao = database.taskDao(),
                occurrenceDao = database.occurrenceDao(),
                batchDao = database.completionBatchDao(),
                attachmentDao = database.attachmentDao(),
                accountDao = database.gameAccountDao(),
                ownerDao = database.ownerDao(),
                receiptDao = database.receiptDao(),
                receiptLedger = receiptLedger,
                creationOperationDao = database.creationOperationDao(),
                ticker = ticker,
            )
            val attachmentRepository = DefaultAttachmentRepository(
                context = context,
                database = database,
                attachmentDao = database.attachmentDao(),
                taskDao = database.taskDao(),
                occurrenceDao = database.occurrenceDao(),
                orderDao = database.orderDao(),
                accountDao = database.gameAccountDao(),
                ownerDao = database.ownerDao(),
                timeProvider = fixed,
                sync = com.daigan.record.data.repository.AttachmentSync(),
            )

            val owner = ownerRepository.observeActiveOwners().first()
                .firstOrNull { it.owner.nickname == nickname }
                ?.owner
                ?: ownerRepository.createOwner(nickname)
            val existingAccount = database.gameAccountDao().observeActive().first()
                .firstOrNull { it.uid == uid }
            val account = existingAccount?.toModel()
                ?: accountRepository.createAccount(
                    owner.id,
                    AccountDraft(
                        gameName = "续期测试游戏",
                        server = "官服",
                        uid = uid,
                        loginAccount = "renew-login",
                        loginMethod = LoginMethod.VERIFICATION_CODE,
                    ),
                )

            val existingOrders = orderRepository.observeOrdersForOwner(owner.id).first()
            if (existingOrders.none { it.order.title == "过期托管探针" }) {
                val orderId = orderRepository.createHostingOrder(
                    hostingDraft(
                        ownerId = owner.id,
                        accountId = account.id,
                        start = LocalDate.of(2026, 10, 1),
                        end = LocalDate.of(2026, 10, 10),
                        priceYuan = "200.00",
                        tasks = listOf(TaskDraft(title = "旧期签到")),
                        title = "过期托管探针",
                    ),
                )
                val todayTasks = orderRepository.observeTodayHosting().first()
                    .firstOrNull { it.order.id == orderId }
                    ?.tasks
                    ?: emptyList()
                todayTasks.forEach { task ->
                    orderRepository.setOccurrenceCompleted(task.task.id, true)
                }
                val detail = orderRepository.observeOrderDetail(orderId).first()
                val occurrence = detail?.occurrences?.firstOrNull { it.completedAt != null }
                if (occurrence != null && detail.attachments.isEmpty()) {
                    val source = writeTestJpeg(context, "probe-old-checkin.jpg")
                    attachmentRepository.addAttachment(
                        taskDefinitionId = null,
                        occurrenceId = occurrence.id,
                        sourceUri = Uri.fromFile(source),
                    )
                }
                assertNotNull(occurrence)
            }
            assertTrue(
                database.orderDao().findAllHostingOrders().any { it.title == "过期托管探针" },
            )
        } finally {
            database.close()
        }
    }
}
