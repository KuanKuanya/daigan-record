package com.daigan.record.testing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the deterministic test scheduler itself.
 *
 * One fake-clock advance must wake *every* parked ticker, not merely the single receiver
 * a conflated channel would hand one element to. A Today collector and an order-detail
 * collector are parked on the same boundary; a scheduler that wakes only one leaves the
 * other parked, so its refresh never arrives and the bounded timeout fails the test with
 * a clear message instead of hanging it. No real sleeps are used to hide a wake-up bug.
 */
@RunWith(AndroidJUnit4::class)
class ManualBoundarySchedulerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var env: OrderTestEnvironment

    private val today: LocalDate = LocalDate.of(2026, 10, 17)

    @Before
    fun setUp() {
        File(context.filesDir, "attachments").deleteRecursively()
        env = OrderTestEnvironment(context)
    }

    @Test
    fun oneClockAdvanceRefreshesBothTodayAndDetailCollectors() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, start = today.minusDays(2), end = today.plusDays(5)),
        )

        var latestToday: LocalDate? = null
        var latestDetail: LocalDate? = null

        val todayJob = launch {
            env.orderRepository.observeTodayHosting().collect { hostings ->
                latestToday = hostings.firstOrNull { it.order.id == orderId }?.today
            }
        }
        val detailJob = launch {
            env.orderRepository.observeOrderDetail(orderId).collect { detail ->
                latestDetail = detail?.today
            }
        }

        try {
            // Both collectors reach their boundary and park before the clock moves.
            env.boundaryWaiter.awaitParked(2)
            assertEquals(2, env.boundaryWaiter.parkedCount())

            // Shanghai daily refresh is 04:00, so the 2026-10-18 game day begins at
            // 2026-10-17T20:00Z; the 21:00Z advance sits inside that new game day.
            env.boundaryWaiter.advanceTo(Instant.parse("2026-10-17T21:00:00Z"))

            // Bounded: a scheduler that wakes only one parked ticker would hang here.
            withTimeout(5_000) {
                while (latestToday != newGameDay || latestDetail != newGameDay) yield()
            }

            assertEquals(newGameDay, latestToday)
            assertEquals(newGameDay, latestDetail)
        } finally {
            todayJob.cancel()
            detailJob.cancel()
        }
    }

    private val newGameDay: LocalDate = LocalDate.of(2026, 10, 18)
}
