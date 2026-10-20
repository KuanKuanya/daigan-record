package com.kuankuan.daigan.data.repository

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Coordinates database transactions, published files, exports and dataset replacement. */
object DatasetGate {
    private data class State(val activeWrites: Int = 0, val replacementRunning: Boolean = false)
    private class Permit : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Permit>
    }

    private val mutex = Mutex()
    private val exclusiveMutex = Mutex()
    private val state = MutableStateFlow(State())
    @Volatile private var generation = 0L
    @Volatile private var recoveryRequired = false

    fun requireRecovery() { recoveryRequired = true; ApplicationReadiness.fail() }
    fun recoveryFinished() { recoveryRequired = false }
    val isReplacing: Boolean get() = state.value.replacementRunning
    val activity = state.map { it.replacementRunning }.distinctUntilChanged()

    /** Called inside the exclusive section after the durable replacement commits. */
    fun datasetReplaced() { generation++ }

    suspend fun enterWrite() {
        val requestedGeneration = generation
        while (true) {
            check(!recoveryRequired) { "资料恢复尚未完成，请先重试恢复。" }
            val acquired = mutex.withLock {
                val current = state.value
                if (current.replacementRunning) false else {
                    check(requestedGeneration == generation) { "资料已被恢复替换，请重新打开页面后操作。" }
                    state.value = current.copy(activeWrites = current.activeWrites + 1)
                    true
                }
            }
            if (acquired) return
            state.first { !it.replacementRunning }
        }
    }

    /** Reentrant across nested repository transactions; never waits holding a DB lock. */
    suspend fun <T> withWrite(block: suspend () -> T): T {
        if (coroutineContext[Permit] != null) return block()
        enterWrite()
        try {
            return withContext(Permit()) { block() }
        } finally {
            withContext(NonCancellable) { exitWrite() }
        }
    }

    suspend fun exitWrite() {
        mutex.withLock {
            val current = state.value
            check(current.activeWrites > 0) { "Unbalanced dataset permit" }
            state.value = current.copy(activeWrites = current.activeWrites - 1)
        }
    }

    suspend fun <T> withExclusiveDatasetAccess(block: suspend () -> T): T =
        withExclusiveDatasetReplacement(block)

    suspend fun <T> withExclusiveDatasetReplacement(block: suspend () -> T): T {
        if (coroutineContext[Permit] != null) return block()
        return exclusiveMutex.withLock {
            mutex.withLock { state.value = state.value.copy(replacementRunning = true) }
            try {
                state.first { it.activeWrites == 0 }
                withContext(Permit()) { block() }
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock { state.value = state.value.copy(replacementRunning = false) }
                }
            }
        }
    }

    suspend fun awaitIdle() {
        if (coroutineContext[Permit] == null) state.first { !it.replacementRunning }
    }
}
