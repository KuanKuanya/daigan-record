package com.daigan.record.core.time

import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/**
 * Suspends until a scheduled boundary is reached.
 *
 * [now] and [target] always belong to the same clock domain: both are read from the
 * ticker's [TimeProvider]. Implementations must derive their wait from that pair and must
 * never consult a second clock, otherwise a boundary scheduled from an injected clock can
 * look like it is already in the past and the wait returns without suspending.
 */
fun interface BoundaryWaiter {
    suspend fun await(now: Instant, target: Instant)
}

/**
 * Production waiter: sleeps for the distance between the caller's [now] and [target].
 *
 * The delay is computed from the supplied [now], not from [Instant.now]. That keeps the
 * scheduler inside one clock domain: with a fixed or historical clock the target is a
 * *future* instant relative to that clock and the wait really suspends instead of
 * returning immediately and spinning the ticker flow.
 *
 * The wait never drops below [MIN_SUSPEND_MILLIS]. A zero-length wait resumes without
 * suspending, so [delay] would not act as a cancellation point and a stale clock could
 * burn a core inside the ticker loop. The minimum also makes [delay] cooperative:
 * cancellation is observed within a fraction of a second.
 */
class RealBoundaryWaiter @Inject constructor() : BoundaryWaiter {
    override suspend fun await(now: Instant, target: Instant) {
        val millis = Duration.between(now, target).toMillis()
        delay(millis.coerceAtLeast(MIN_SUSPEND_MILLIS))
    }

    companion object {
        /** Longest a wait may be shortened to. Also the cancellation check interval. */
        const val MIN_SUSPEND_MILLIS: Long = 200
    }
}

/**
 * Testable time signal shared by every time-dependent flow.
 *
 * A subscription emits the injected clock value immediately and then again after each
 * boundary produced by [nextBoundary], recomputed from the latest emitted instant.
 * Waiting is bounded by [MAX_WAIT] so a manual clock change or a stale schedule cannot
 * freeze a screen.
 *
 * Guarantees that keep the loop well-behaved:
 * - Every iteration suspends: [BoundaryWaiter.await] is only ever called with a target
 *   strictly after [now], and the iteration is preceded by [ensureActive]. A frozen or
 *   historical clock therefore cannot create a hot loop.
 * - Collection ends with the collector: `first()` and cancellation stop it promptly,
 *   no background service and no polling.
 * - [distinctUntilChanged] only removes duplicate values for downstream consumers; it is
 *   never used as the cancellation mechanism, because the upstream loop would keep
 *   producing values that are deleted before any suspension point.
 */
class TimeTicker @Inject constructor(
    private val timeProvider: TimeProvider,
    private val waiter: BoundaryWaiter,
) {
    fun now(): Instant = timeProvider.now()

    fun ticks(nextBoundary: (Instant) -> Instant): Flow<Instant> = flow {
        while (true) {
            currentCoroutineContext().ensureActive()
            val now = timeProvider.now()
            emit(now)
            val requested = nextBoundary(now)
            val safeTarget = when {
                requested.isAfter(now) -> minOf(requested, now.plus(MAX_WAIT))
                // A boundary that is not strictly in the future cannot be waited for
                // without suspending; fall back to the bounded refresh instead.
                else -> now.plus(MAX_WAIT)
            }
            waiter.await(now, safeTarget)
        }
    }.distinctUntilChanged()

    companion object {
        /** Upper bound for one wait: also acts as a safety net for clock changes. */
        val MAX_WAIT: Duration = Duration.ofHours(1)
    }
}

/** Earliest refresh boundary across [rules], with a daily fallback. */
fun nextBoundaryAcross(rules: List<GameRefreshRules>, now: Instant): Instant =
    rules.minOfOrNull { GameCalendar.nextRefreshBoundary(now, it) }
        ?: now.plus(TimeTicker.MAX_WAIT)
