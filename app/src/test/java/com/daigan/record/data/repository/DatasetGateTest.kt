package com.daigan.record.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DatasetGateTest {
    @Test fun exclusiveOperationsCannotOverlap() = runTest {
        val release = CompletableDeferred<Unit>()
        val first = launch { DatasetGate.withExclusiveDatasetAccess { release.await() } }
        runCurrent()
        var secondEntered = false
        val second = launch { DatasetGate.withExclusiveDatasetAccess { secondEntered = true } }
        runCurrent()
        val overlapped = secondEntered
        release.complete(Unit)
        first.join(); second.join()
        assertFalse("A restore/export must not overlap another exclusive operation", overlapped)
    }

    @Test fun cancelledWriteReleasesItsPermit() = runTest {
        val wait = CompletableDeferred<Unit>()
        val writer = launch { DatasetGate.withWrite { wait.await() } }
        runCurrent()
        writer.cancelAndJoin()
        val exclusive = async { DatasetGate.withExclusiveDatasetAccess { true } }
        runCurrent()
        val acquired = exclusive.isCompleted
        exclusive.cancelAndJoin()
        assertTrue("Cancellation must not strand an active-write count", acquired)
    }
}
