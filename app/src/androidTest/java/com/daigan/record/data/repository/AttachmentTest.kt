package com.daigan.record.data.repository

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.shortDraft
import com.daigan.record.testing.writeTestImage
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "attachment-test.db"
    private lateinit var env: OrderTestEnvironment

    @Before
    fun setUp() {
        context.deleteDatabase(databaseName)
        File(context.filesDir, "attachments").deleteRecursively()
        env = OrderTestEnvironment(context, databaseName = databaseName, inMemory = false)
    }

    @After
    fun tearDown() {
        runCatching { env.close() }
        context.deleteDatabase(databaseName)
        File(context.filesDir, "attachments").deleteRecursively()
    }

    private suspend fun seedShortTask(): Pair<String, String> {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createShortOrder(shortDraft(owner.id, account.id))
        val taskId = env.orderRepository.observeOrderDetail(orderId).first()!!.tasks.single().id
        return orderId to taskId
    }

    @Test
    fun screenshotIsCopiedIntoPrivateStorageAndSurvivesRestart() = runBlocking {
        val (orderId, taskId) = seedShortTask()
        val source = writeTestImage(context, "first.jpg")
        val attachment = env.attachmentRepository.addAttachment(
            taskDefinitionId = taskId,
            occurrenceId = null,
            sourceUri = Uri.fromFile(source),
        )
        val stored = env.attachmentRepository.fileFor(attachment)
        assertTrue(stored.exists())
        assertTrue(stored.canonicalPath.startsWith(File(context.filesDir, "attachments").canonicalPath))
        assertEquals(attachment.sizeBytes, stored.length())
        assertEquals(1, env.attachmentRepository.observeForOrder(orderId).first().size)

        env.close(deleteDatabaseFile = false)
        env = OrderTestEnvironment(context, databaseName = databaseName, inMemory = false)
        val afterRestart = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(1, afterRestart.attachments.size)
        assertTrue(File(context.filesDir, "attachments/${afterRestart.attachments.single().relativePath}").exists())
    }

    @Test
    fun replacementKeepsTheOldAttachmentWhenTheNewSourceFails() = runBlocking {
        val (orderId, taskId) = seedShortTask()
        val old = env.attachmentRepository.addAttachment(
            taskId,
            null,
            Uri.fromFile(writeTestImage(context, "old.jpg")),
        )
        val oldFile = env.attachmentRepository.fileFor(old)

        val failure = runCatching {
            env.attachmentRepository.replaceAttachment(
                old.id,
                Uri.parse("content://com.daigan.record.missing/image/1"),
            )
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue("Old row survives", env.attachmentRepository.observeForOrder(orderId).first().any { it.id == old.id })
        assertTrue("Old file survives", oldFile.exists())
        assertEquals(1, env.attachmentRepository.observeForOrder(orderId).first().size)
    }

    @Test
    fun replacementSwapsTheReferenceAndRemovesTheOldFile() = runBlocking {
        val (orderId, taskId) = seedShortTask()
        val old = env.attachmentRepository.addAttachment(
            taskId,
            null,
            Uri.fromFile(writeTestImage(context, "old.jpg")),
        )
        val oldFile = env.attachmentRepository.fileFor(old)
        val replacement = env.attachmentRepository.replaceAttachment(
            old.id,
            Uri.fromFile(writeTestImage(context, "new.png")),
        )
        assertFalse(oldFile.exists())
        assertTrue(env.attachmentRepository.fileFor(replacement).exists())
        val stored = env.attachmentRepository.observeForOrder(orderId).first()
        assertEquals(1, stored.size)
        assertEquals(replacement.id, stored.single().id)
    }

    @Test
    fun deleteRemovesTheReferenceBeforeTheFile() = runBlocking {
        val (orderId, taskId) = seedShortTask()
        val attachment = env.attachmentRepository.addAttachment(
            taskId,
            null,
            Uri.fromFile(writeTestImage(context)),
        )
        val file = env.attachmentRepository.fileFor(attachment)
        env.attachmentRepository.deleteAttachment(attachment.id)
        assertTrue(env.attachmentRepository.observeForOrder(orderId).first().isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun concurrentCleanupCannotDeleteAFilePublishedAfterItsReferenceSnapshot() = runBlocking {
        val (orderId, taskId) = seedShortTask()
        val reachedBarrier = CompletableDeferred<Unit>()
        val releaseBarrier = CompletableDeferred<Unit>()
        env.attachmentSync.hooks = object : AttachmentSync.Hooks {
            override suspend fun afterFinalFilePublished(relativePath: String) {
                reachedBarrier.complete(Unit)
                releaseBarrier.await()
            }
        }

        val publishing = async {
            env.attachmentRepository.addAttachment(
                taskId,
                null,
                Uri.fromFile(writeTestImage(context, "concurrent.jpg")),
            )
        }

        // The file is published and the lock is still held, so a cleanup that arrives
        // now must block rather than scan a half-committed state.
        withTimeout(5_000) { reachedBarrier.await() }
        assertTrue(env.attachmentSync.isLocked)

        val cleaning = async { env.attachmentRepository.cleanupOrphans() }
        yield()
        assertFalse("Cleanup must not run while the publication holds the lock", cleaning.isCompleted)

        // Let the publication commit its reference, then let cleanup take its snapshot.
        releaseBarrier.complete(Unit)
        val attachment = withTimeout(5_000) { publishing.await() }
        val deleted = withTimeout(5_000) { cleaning.await() }
        env.attachmentSync.hooks = null

        assertEquals("The newly referenced file must never be treated as an orphan", 0, deleted)
        assertTrue(env.attachmentRepository.fileFor(attachment).exists())
        assertEquals(1, env.attachmentRepository.observeForOrder(orderId).first().size)
    }

    @Test
    fun concurrentCleanupTakesItsSnapshotAfterAnInFlightPublicationCommits() = runBlocking {
        val (_, taskId) = seedShortTask()
        val reachedBarrier = CompletableDeferred<Unit>()
        val releaseBarrier = CompletableDeferred<Unit>()
        env.attachmentSync.hooks = object : AttachmentSync.Hooks {
            override suspend fun afterReferenceSnapshot(referenced: Set<String>) {
                reachedBarrier.complete(Unit)
                releaseBarrier.await()
            }
        }

        val cleaning = async { env.attachmentRepository.cleanupOrphans() }
        withTimeout(5_000) { reachedBarrier.await() }

        // While cleanup holds the lock, a publication must wait instead of exposing a
        // final file that the in-flight snapshot cannot see.
        val publishing = async {
            env.attachmentRepository.addAttachment(
                taskId,
                null,
                Uri.fromFile(writeTestImage(context, "after-cleanup.jpg")),
            )
        }
        yield()
        assertFalse("Publication must not proceed while cleanup holds the lock", publishing.isCompleted)

        releaseBarrier.complete(Unit)
        val deleted = withTimeout(5_000) { cleaning.await() }
        val attachment = withTimeout(5_000) { publishing.await() }
        env.attachmentSync.hooks = null

        assertEquals(0, deleted)
        assertTrue(env.attachmentRepository.fileFor(attachment).exists())
    }

    @Test
    fun canceledOrUnreadableSelectionNeverCreatesABrokenAttachment() = runBlocking {
        val (orderId, taskId) = seedShortTask()
        assertNotNull(
            runCatching {
                env.attachmentRepository.addAttachment(taskId, null, Uri.EMPTY)
            }.exceptionOrNull(),
        )
        assertNotNull(
            runCatching {
                env.attachmentRepository.addAttachment(
                    taskId,
                    null,
                    Uri.parse("content://com.daigan.record.missing/image/2"),
                )
            }.exceptionOrNull(),
        )
        assertTrue(env.attachmentRepository.observeForOrder(orderId).first().isEmpty())
        assertTrue(
            File(context.filesDir, "attachments").listFiles().orEmpty().none { it.name.endsWith(".jpg") },
        )
    }

    @Test
    fun ownershipMustBeExactlyOneTaskOrOneCheckIn() = runBlocking {
        val (_, taskId) = seedShortTask()
        val uri = Uri.fromFile(writeTestImage(context))
        assertNotNull(
            runCatching { env.attachmentRepository.addAttachment(null, null, uri) }.exceptionOrNull(),
        )
        assertNotNull(
            runCatching { env.attachmentRepository.addAttachment(taskId, "occurrence", uri) }
                .exceptionOrNull(),
        )
    }

    @Test
    fun archivedAccountRejectsNewScreenshots() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createShortOrder(shortDraft(owner.id, account.id))
        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        val task = detail.tasks.single()
        env.orderRepository.setShortTaskCompleted(task.id, true)
        env.accountRepository.archiveAccount(account.id)

        val failure = runCatching {
            env.attachmentRepository.addAttachment(
                task.id,
                null,
                Uri.fromFile(writeTestImage(context)),
            )
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(ARCHIVED_ACCOUNT_ORDER_MESSAGE, failure!!.message)
    }

    @Test
    fun cleanupDeletesOnlyUnreferencedFiles() = runBlocking {
        val (orderId, taskId) = seedShortTask()
        val attachment = env.attachmentRepository.addAttachment(
            taskId,
            null,
            Uri.fromFile(writeTestImage(context)),
        )
        val referenced = env.attachmentRepository.fileFor(attachment)
        val directory = File(context.filesDir, "attachments")
        val orphan = File(directory, "orphan-${System.nanoTime()}.jpg").apply { writeBytes(byteArrayOf(9)) }

        val deleted = env.attachmentRepository.cleanupOrphans()
        assertTrue(deleted >= 1)
        assertFalse(orphan.exists())
        assertTrue(referenced.exists())
        assertEquals(1, env.attachmentRepository.observeForOrder(orderId).first().size)
    }

    @Test
    fun hostingCheckInScreenshotsAttachToTheOccurrence() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.clock.set(java.time.Instant.parse("2026-10-17T02:00:00Z"))
        val orderId = env.orderRepository.createHostingOrder(
            com.daigan.record.testing.hostingDraft(
                owner.id,
                account.id,
                java.time.LocalDate.of(2026, 10, 15),
                java.time.LocalDate.of(2026, 10, 20),
            ),
        )
        val task = env.orderRepository.observeTodayHosting().first().single().tasks.single().task
        env.orderRepository.setOccurrenceCompleted(task.id, true)
        val occurrence = env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.single()
        env.attachmentRepository.addAttachment(
            taskDefinitionId = null,
            occurrenceId = occurrence.id,
            sourceUri = Uri.fromFile(writeTestImage(context, "checkin.jpg")),
        )
        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(1, detail.attachments.size)
        assertEquals(occurrence.id, detail.attachments.single().occurrenceId)
        assertNull(detail.attachments.single().taskDefinitionId)
    }
}
