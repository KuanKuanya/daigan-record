package com.daigan.record.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared publication protocol for attachment files and their database references.
 *
 * Every operation that can create, swap, remove or reclaim a final file holds the
 * single lock for the *complete* file-plus-database interval:
 *
 * - add/replace: from before the final file exists until its row is committed;
 * - delete: from removing the row until the file is gone;
 * - cleanup: from reading the reference snapshot until every deletion is done.
 *
 * Cleanup therefore cannot interleave with a publication: its snapshot cannot go
 * stale, and an in-flight final file can never be mistaken for an orphan. A delay or
 * a re-check alone would leave both reported interleavings possible.
 */
@Singleton
class AttachmentSync @Inject constructor() {

    private val mutex = Mutex()

    /**
     * Deterministic barriers used by concurrency tests. Production leaves this null;
     * the operations themselves are unchanged.
     */
    var hooks: Hooks? = null

    val isLocked: Boolean get() = mutex.isLocked

    suspend fun <T> withExclusive(block: suspend () -> T): T = mutex.withLock { block() }

    interface Hooks {
        /** Holding the lock, after the final file exists but before its row is committed. */
        suspend fun afterFinalFilePublished(relativePath: String) = Unit

        /** Holding the lock, after the reference snapshot was read. */
        suspend fun afterReferenceSnapshot(referenced: Set<String>) = Unit
    }
}
