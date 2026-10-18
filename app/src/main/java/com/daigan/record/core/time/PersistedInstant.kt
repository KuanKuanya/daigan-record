package com.daigan.record.core.time

import java.time.Instant

/**
 * Room's `InstantConverters` persist an [Instant] as epoch **milliseconds**. Any value
 * that is written and later read back therefore loses sub-millisecond precision.
 *
 * Retryable financial commands must normalize to that same precision *before* they are
 * validated, fingerprinted, compared with a stored row or returned to a caller. Doing it
 * only in the UI dialog is not enough: the repository contract has to be correct on its
 * own, including when a caller passes a nanosecond-precision `Instant`.
 */
object PersistedInstant {

    /** The exact value that a database round trip will produce. */
    fun normalize(instant: Instant): Instant = Instant.ofEpochMilli(instant.toEpochMilli())

    /** Normalized epoch milliseconds, used by request fingerprints. */
    fun normalizedMillis(instant: Instant): Long = instant.toEpochMilli()
}

/** Convenience alias for [PersistedInstant.normalize]. */
fun Instant.toPersistedPrecision(): Instant = PersistedInstant.normalize(this)
