package com.kuankuan.daigan.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kuankuan.daigan.core.time.StatisticsRange
import com.kuankuan.daigan.data.model.PaymentChannel
import com.kuankuan.daigan.data.model.PaymentRequest
import com.kuankuan.daigan.testing.OrderTestEnvironment
import com.kuankuan.daigan.testing.hostingDraft
import com.kuankuan.daigan.testing.shortDraft
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrderMaintenanceTest {
    private lateinit var env: OrderTestEnvironment
    private val day = LocalDate.of(2026, 10, 17)
    @Before fun setup() { env = OrderTestEnvironment(ApplicationProvider.getApplicationContext<Context>()) }
    @After fun close() { env.close() }

    @Test fun extendExpiredPeriodPreservesHistoryAndPrice() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val id = env.orderRepository.createHostingOrder(hostingDraft(owner.id, account.id, day.minusDays(3), day.minusDays(1)))
        val before = env.orderRepository.observeOrderDetail(id).first()!!
        val period = before.currentPeriod!!
        env.orderRepository.setHistoricalCompletion(id, period.id, before.tasks.first().id, day.minusDays(1), true)
        env.orderRepository.updateOrderDates(id, period.id, period.startDate, day.plusDays(5))
        val after = env.orderRepository.observeOrderDetail(id).first()!!
        assertEquals(1, after.periods.size)
        assertEquals(period.id, after.currentPeriod!!.id)
        assertEquals(period.priceCents, after.currentPeriod!!.priceCents)
        assertEquals(1, after.occurrences.count { it.completed })
        assertEquals(day.plusDays(5), after.currentPeriod!!.endDate)
        assertTrue(env.orderRepository.observeTodayHosting().first().single().tasks.isNotEmpty())
        assertTrue(runCatching {
            env.orderRepository.updateOrderDates(id, period.id, period.startDate, day.minusDays(2))
        }.isFailure)
        assertEquals(day.plusDays(5), env.database.orderDao().findPeriod(period.id)!!.endDate)
    }

    @Test fun unusedOrderDeletesItsChildrenAndCreationIdentity() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val id = env.orderRepository.createHostingOrder(hostingDraft(owner.id, account.id, day, day.plusDays(5)))
        env.orderRepository.deleteUnusedOrder(id)
        assertNull(env.database.orderDao().findById(id))
        assertTrue(env.database.orderDao().findTasks(id).isEmpty())
        assertTrue(env.database.orderDao().findPeriods(id).isEmpty())
        assertTrue(env.database.creationOperationDao().findAll().none { it.orderId == id })
    }

    @Test fun usedOrderCannotDeleteButCancellationKeepsWorkAndStopsWrites() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val id = env.orderRepository.createShortOrder(shortDraft(owner.id, account.id))
        val task = env.database.orderDao().findTasks(id).first()
        env.orderRepository.setShortTaskCompleted(task.id, true)
        assertTrue(runCatching { env.orderRepository.deleteUnusedOrder(id) }.isFailure)
        env.orderRepository.cancelOrder(id)
        env.orderRepository.cancelOrder(id)
        assertNotNull(env.database.orderDao().findById(id)!!.cancelledAt)
        assertNotNull(env.database.taskDao().findById(task.id)!!.completedAt)
        assertTrue(env.orderRepository.observeTodayShort().first().isEmpty())
        assertTrue(runCatching { env.orderRepository.setShortTaskCompleted(task.id, false) }.isFailure)
        assertTrue(runCatching { env.orderRepository.updateOrderDates(id, null, null, day) }.isFailure)
    }

    @Test fun cancellationRetainsReceiptsAndIncomeButRemovesDebtAndOrderCount() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val id = env.orderRepository.createShortOrder(shortDraft(owner.id, account.id))
        val order = env.database.orderDao().findById(id)!!
        env.receiptRepository.recordPayment(PaymentRequest(
            orderId = id, periodId = null, amountCents = order.priceCents!!,
            receivedAt = env.clock.now(), channel = PaymentChannel.WECHAT,
            note = null, operationId = "paid-before-cancel",
        ))
        env.orderRepository.cancelOrder(id)
        val stats = env.statisticsRepository.loadStatistics(StatisticsRange.ALL, ZoneId.of("Asia/Shanghai"), env.clock.now())
        assertEquals(order.priceCents, stats.receivedCents)
        assertEquals(0L, stats.outstandingCents)
        assertEquals(0, stats.orderCount)
        assertEquals(1, env.database.receiptDao().findForOrder(id).size)
        assertTrue(runCatching { env.orderRepository.deleteUnusedOrder(id) }.isFailure)
    }
}
