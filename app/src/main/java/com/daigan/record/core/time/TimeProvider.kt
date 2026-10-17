package com.daigan.record.core.time

import java.time.Instant
import javax.inject.Inject

/** Injected clock so tests can control creation timestamps without touching the UI. */
fun interface TimeProvider {
    fun now(): Instant
}

class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun now(): Instant = Instant.now()
}
