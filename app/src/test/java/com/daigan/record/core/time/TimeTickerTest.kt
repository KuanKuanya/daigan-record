package com.daigan.record.core.time

import com.daigan.record.data.model.OccurrenceRules
import com.daigan.record.data.model.RecurrenceType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the Phase 2 instrumented hang.
 *
 * The failing combination was a fixed historical clock (2026-10-05) driving
 * [TimeTicker] while [RealBoundaryWaiter] measured its delay against `Instant.now()`.
 * The boundary computed from the injected clock was already in the real past, so the
 * wait returned without suspending, `distinctUntilChanged` deleted every repeat value
 * before it could reach the collector, and the upstream loop spun a core forever: the
 * collection never ended and `first()` could not cancel it.
 *
 * Every test here is bounded. A hot loop is detected by a [TimeProvider] that refuses to
 * be read more than [HOT_LOOP_READ_LIMIT] times, which fails the test in milliseconds
 * with an explicit message instead of hanging the build. Suspension-based waits use
 * `withTimeout`, never sleeps that "eventually" work.
 */
class TimeTickerTest {

    private val shanghai = GameRefreshRules(
        zoneId = ZoneId.of("Asia/Shanghai"),
        dailyRefresh = LocalTime.of(4, 0),
        weeklyRefreshDay = DayOfWeek.MONDAY,
        weeklyRefreshTime = LocalTime.of(5, 0),
    )

    /** 2026-10-17 10:00 Shanghai. */
    private val start = Instant.parse("2026-10-17T02:00:00Z")

    /** 2026-10-18 04:00 Shanghai: the next daily refresh. */
    private val nextDaily = Instant.parse("2026-10-17T20:00:00Z")

    /** 2026-10-19 05:00 Shanghai: the next weekly refresh. */
    private val nextWeekly = Instant.parse("2026-10-18T21:00:00Z")

    private fun ticker(clock: TimeProvider, waiter: BoundaryWaiter) = TimeTicker(clock, waiter)

    private fun TimeTicker.ticking(): kotlinx.coroutines.flow.Flow<Instant> =
        ticks { now -> GameCalendar.nextRefreshBoundary(now, shanghai) }

    // --- clock domain: a fixed historical clock must park, not spin ---------------------

    @Test
    fun fixedHistoricalClockCannotCreateAHotLoop() = runBlocking {
        // 2026-10-05 10:00 Shanghai, twelve days before the wall clock of this machine.
        val historical = Instant.parse("2026-10-05T02:00:00Z")
        val provider = BoundedTimeProvider(historical)
        val job = launch(Dispatchers.Default) { ticker(provider, RealBoundaryWaiter()).ticking().collect { } }

        try {
            // Real elapsed time on purpose: the bug was a busyloop, not a wrong value.
            delay(500)
            assertTrue("A parked ticker must still be running", job.isActive)
            assertEquals(
                "A frozen clock must schedule one suspension, not re-read time in a loop",
                1,
                provider.calls,
            )
        } finally {
            withTimeout(2_000) { job.cancelAndJoin() }
        }
    }

    @Test
    fun firstReturnsImmediatelyOnAFixedHistoricalClock() = runBlocking {
        val historical = Instant.parse("2026-10-05T02:00:00Z")
        val provider = BoundedTimeProvider(historical)
        val value = withTimeout(3_000) {
            ticker(provider, RealBoundaryWaiter()).ticking().first()
        }
        assertEquals(historical, value)
        assertEquals("first() must not spin after it already has a value", 1, provider.calls)
    }

    // --- cancellation -----------------------------------------------------------------

    @Test
    fun firstCancelsAParkedTickerAndTheNextCollectionSeesTheNewInstant() = runBlocking {
        val clock = FakeClock(start)
        val waiter = GatedBoundaryWaiter(clock)
        val subject = ticker(clock, waiter)

        val firstValue = withTimeout(3_000) { subject.ticking().first() }
        assertEquals(start, firstValue)
        assertEquals("first() must not leave a parked subscriber behind", 0, waiter.parkedCount())

        clock.current = nextDaily
        val secondValue = withTimeout(3_000) { subject.ticking().first() }
        assertEquals(nextDaily, secondValue)
        assertEquals(0, waiter.parkedCount())
    }

    @Test
    fun collectorCancellationTerminatesPromptly() = runBlocking {
        val clock = FakeClock(start)
        val waiter = GatedBoundaryWaiter(clock)
        val job = launch(Dispatchers.Default) { ticker(clock, waiter).ticking().collect { } }

        waiter.awaitParked(1)
        assertEquals(1, waiter.parkedCount())

        withTimeout(2_000) { job.cancelAndJoin() }
        assertFalse("A cancelled collector must stop the ticker", job.isActive)
        withTimeout(2_000) {
            while (waiter.parkedCount() != 0) yield()
        }
    }

    // --- multiple subscribers ----------------------------------------------------------

    @Test
    fun oneClockAdvanceWakesEverySubscriber() = runBlocking {
        val clock = FakeClock(Instant.parse("2026-10-17T19:30:00Z"))
        val waiter = GatedBoundaryWaiter(clock)
        val subject = ticker(clock, waiter)
        val today = AtomicReference<Instant?>(null)
        val detail = AtomicReference<Instant?>(null)

        val todayJob: Job = launch(Dispatchers.Default) { subject.ticking().collect { today.set(it) } }
        val detailJob: Job = launch(Dispatchers.Default) { subject.ticking().collect { detail.set(it) } }

        try {
            waiter.awaitParked(2)
            assertEquals(clock.current, today.get())
            assertEquals(clock.current, detail.get())

            waiter.advanceTo(nextDaily)

            // Bounded: a conflated wake-up would leave one subscriber parked forever.
            withTimeout(5_000) {
                while (today.get() != nextDaily || detail.get() != nextDaily) yield()
            }
            assertEquals(nextDaily, today.get())
            assertEquals(nextDaily, detail.get())
            assertEquals(
                LocalDate.of(2026, 10, 18),
                GameCalendar.gameDate(requireNotNull(today.get()), shanghai),
            )
        } finally {
            todayJob.cancel()
            detailJob.cancel()
        }
    }

    // --- daily and weekly boundaries ----------------------------------------------------

    @Test
    fun dailyRefreshIsScheduledFromTheInjectedClock() = runBlocking {
        // 2026-10-17 03:30 Shanghai: 30 minutes before the 04:00 daily refresh. Starting
        // inside the one-hour bound means the parked target is the boundary itself.
        val clock = FakeClock(Instant.parse("2026-10-17T19:30:00Z"))
        val waiter = GatedBoundaryWaiter(clock)
        val latest = AtomicReference<Instant?>(null)
        val job = launch(Dispatchers.Default) { ticker(clock, waiter).ticking().collect { latest.set(it) } }

        try {
            // 04:00 Shanghai on 2026-10-18, computed only from the injected clock.
            waiter.awaitParkedAt(nextDaily)
            assertEquals(listOf(nextDaily), waiter.parkedTargets())

            waiter.advanceTo(nextDaily)
            withTimeout(5_000) { while (latest.get() != nextDaily) yield() }
            assertEquals(
                "The daily refresh starts the 2026-10-18 game day",
                LocalDate.of(2026, 10, 18),
                GameCalendar.gameDate(nextDaily, shanghai),
            )

            // The next weekly boundary is 25 hours away, so the ticker parks on the
            // one-hour bound instead of sleeping past it.
            waiter.awaitParkedAt(nextDaily.plus(TimeTicker.MAX_WAIT))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun weeklyRefreshBoundaryStillSwitchesTheActiveWindow() = runBlocking {
        // Monday 2026-10-19 04:30 Shanghai: the daily refresh already happened, the
        // weekly refresh (05:00) has not, so last week's window is still actionable.
        val beforeWeekly = Instant.parse("2026-10-18T20:30:00Z")
        val clock = FakeClock(beforeWeekly)
        val waiter = GatedBoundaryWaiter(clock)
        val latest = AtomicReference<Instant?>(beforeWeekly)
        val job = launch(Dispatchers.Default) { ticker(clock, waiter).ticking().collect { latest.set(it) } }

        try {
            waiter.awaitParkedAt(nextWeekly)
            assertEquals("W:2026-10-12", weeklyKeyAt(requireNotNull(latest.get())))

            waiter.advanceTo(nextWeekly)
            withTimeout(5_000) { while (latest.get() != nextWeekly) yield() }

            assertEquals(nextWeekly, latest.get())
            assertEquals("W:2026-10-19", weeklyKeyAt(nextWeekly))

            // The following daily boundary is ~23h away, so the ticker deliberately parks
            // on the one-hour bound instead of an unbounded sleep.
            waiter.awaitParkedAt(nextWeekly.plus(TimeTicker.MAX_WAIT))
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun weeklyKeyAt(instant: Instant): String? = OccurrenceRules.slotForNow(
        recurrence = RecurrenceType.WEEKLY,
        weekdays = emptySet(),
        everyNDays = null,
        periodStart = LocalDate.of(2026, 10, 12),
        periodEnd = LocalDate.of(2026, 10, 25),
        today = GameCalendar.gameDate(instant, shanghai),
        rules = shanghai,
        now = instant,
    )?.key

    // --- test doubles ------------------------------------------------------------------

    /**
     * Clock that fails instead of allowing an unbounded read loop. Reading it more than
     * [HOT_LOOP_READ_LIMIT] times without the clock changing is exactly the hang, so the
     * test fails fast with the diagnosis rather than starving the test JVM.
     */
    private class BoundedTimeProvider(private val fixed: Instant) : TimeProvider {
        private val reads = AtomicInteger()

        val calls: Int get() = reads.get()

        override fun now(): Instant {
            val call = reads.incrementAndGet()
            if (call > HOT_LOOP_READ_LIMIT) {
                throw IllegalStateException(
                    "Hot loop detected: the clock was read $call times without a suspension. " +
                        "A scheduled boundary must be waited for in the injected clock's domain.",
                )
            }
            return fixed
        }
    }

    private class FakeClock(var current: Instant) : TimeProvider {
        override fun now(): Instant = current
    }

    /**
     * Deterministic substitute for [RealBoundaryWaiter]: each subscription parks on its
     * own gate, [advanceTo] wakes all of them, and every helper is bounded.
     */
    private class GatedBoundaryWaiter(private val clock: FakeClock) : BoundaryWaiter {
        private class Parked(val target: Instant, val gate: CompletableDeferred<Unit>)

        private val monitor = Any()
        private val parked = mutableListOf<Parked>()

        override suspend fun await(now: Instant, target: Instant) {
            if (!target.isAfter(clock.now())) {
                yield()
                return
            }
            val gate = CompletableDeferred<Unit>()
            synchronized(monitor) { parked += Parked(target, gate) }
            try {
                gate.await()
            } finally {
                synchronized(monitor) { parked.removeAll { it.gate === gate } }
            }
        }

        fun parkedCount(): Int = synchronized(monitor) { parked.size }

        fun parkedTargets(): List<Instant> = synchronized(monitor) { parked.map { it.target } }

        suspend fun awaitParked(count: Int) {
            try {
                withTimeout(PARK_TIMEOUT_MILLIS) {
                    while (parkedCount() < count) yield()
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError(
                    "Timed out waiting for $count parked ticker(s); a wake-up signal was lost.",
                    timeout,
                )
            }
        }

        suspend fun awaitParkedAt(target: Instant) {
            try {
                withTimeout(PARK_TIMEOUT_MILLIS) {
                    while (!parkedTargets().contains(target)) yield()
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError(
                    "Timed out waiting for a ticker parked on $target; " +
                        "parked targets were ${parkedTargets()}.",
                    timeout,
                )
            }
        }

        fun advanceTo(instant: Instant) {
            check(instant.isAfter(clock.now())) { "fake clock must advance, not rewind" }
            clock.current = instant
            val waiting = synchronized(monitor) { parked.toList() }
            waiting.forEach { it.gate.complete(Unit) }
        }
    }

    private companion object {
        /** Far above the one read a parked ticker needs, far below a busyloop. */
        const val HOT_LOOP_READ_LIMIT = 50
        const val PARK_TIMEOUT_MILLIS = 5_000L
    }
}
