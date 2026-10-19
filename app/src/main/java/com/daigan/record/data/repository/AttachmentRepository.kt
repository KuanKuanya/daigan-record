package com.daigan.record.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.local.dao.AttachmentDao
import com.daigan.record.data.local.dao.GameAccountDao
import com.daigan.record.data.local.dao.OccurrenceDao
import com.daigan.record.data.local.dao.OrderDao
import com.daigan.record.data.local.dao.OwnerDao
import com.daigan.record.data.local.dao.TaskDao
import com.daigan.record.data.local.entity.AttachmentEntity
import com.daigan.record.data.local.toModel
import com.daigan.record.data.model.Attachment
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

interface AttachmentRepository {

    fun observeForOrder(orderId: String): Flow<List<Attachment>>

    /**
     * Copies the picked content into private storage, then commits its database row.
     * A canceled picker or an unreadable source throws before any row is written.
     */
    suspend fun addAttachment(
        taskDefinitionId: String?,
        occurrenceId: String?,
        sourceUri: Uri,
    ): Attachment

    /** The old attachment (row and file) stays intact if anything about the new one fails. */
    suspend fun replaceAttachment(attachmentId: String, sourceUri: Uri): Attachment

    /** The database reference is removed first; the file is deleted afterwards. */
    suspend fun deleteAttachment(attachmentId: String)

    fun fileFor(attachment: Attachment): File

    /** Deletes unreferenced files and stale temp files. Returns the deleted count. */
    suspend fun cleanupOrphans(): Int
}

@Singleton
class DefaultAttachmentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: RecordDatabase,
    private val attachmentDao: AttachmentDao,
    private val taskDao: TaskDao,
    private val occurrenceDao: OccurrenceDao,
    private val orderDao: OrderDao,
    private val accountDao: GameAccountDao,
    private val ownerDao: OwnerDao,
    private val timeProvider: TimeProvider,
    private val sync: AttachmentSync,
) : AttachmentRepository {

    /**
     * A published screenshot and its database row must not interleave with a dataset
     * replacement, so the attachment lock is nested inside the dataset write gate.
     * [AttachmentSync] still serialises publications against each other and against
     * orphan cleanup.
     */
    private suspend fun <T> withExclusivePublished(block: suspend () -> T): T =
        DatasetGate.withWrite { sync.withExclusive { block() } }

    private data class StagedFile(
        val id: String,
        val mimeType: String,
        val tempFile: File,
        val extension: String,
    )

    override fun observeForOrder(orderId: String): Flow<List<Attachment>> =
        attachmentDao.observeForOrder(orderId).map { rows -> rows.map { it.toModel() } }

    override suspend fun addAttachment(
        taskDefinitionId: String?,
        occurrenceId: String?,
        sourceUri: Uri,
    ): Attachment {
        require((taskDefinitionId == null) != (occurrenceId == null)) {
            "截图必须且只能归属到一个任务或一次打卡"
        }
        return withExclusivePublished {
            val staged = stage(sourceUri)
            val finalFile = commit(staged)
            val entityId = UUID.randomUUID().toString()
            try {
                sync.hooks?.afterFinalFilePublished(finalFile.name)
                database.withDatasetTransaction {
                    validateTarget(taskDefinitionId, occurrenceId)
                    val now = timeProvider.now()
                    val entity = AttachmentEntity(
                        id = entityId,
                        taskDefinitionId = taskDefinitionId,
                        occurrenceId = occurrenceId,
                        relativePath = finalFile.name,
                        mimeType = staged.mimeType,
                        sizeBytes = finalFile.length(),
                        createdAt = now,
                    )
                    attachmentDao.insert(entity)
                    entity.toModel()
                }
            } catch (error: Throwable) {
                // The database reference must exist before a file is considered
                // attached. A cancellation that arrives after the commit is handled
                // by checking the reference instead of deleting a live file.
                if (attachmentDao.findById(entityId) == null) {
                    finalFile.delete()
                }
                throw error
            }
        }
    }

    override suspend fun replaceAttachment(
        attachmentId: String,
        sourceUri: Uri,
    ): Attachment = withExclusivePublished {
        val old = database.withDatasetTransaction {
            val existing = attachmentDao.findById(attachmentId)
                ?: throw IllegalArgumentException("截图不存在")
            validateTarget(existing.taskDefinitionId, existing.occurrenceId)
            existing
        }
        val staged = stage(sourceUri)
        val finalFile = commit(staged)
        val newId = UUID.randomUUID().toString()
        val replacement = try {
            sync.hooks?.afterFinalFilePublished(finalFile.name)
            database.withDatasetTransaction {
                val current = attachmentDao.findById(attachmentId)
                    ?: throw IllegalArgumentException("截图不存在")
                validateTarget(current.taskDefinitionId, current.occurrenceId)
                val now = timeProvider.now()
                val entity = AttachmentEntity(
                    id = newId,
                    taskDefinitionId = current.taskDefinitionId,
                    occurrenceId = current.occurrenceId,
                    relativePath = finalFile.name,
                    mimeType = staged.mimeType,
                    sizeBytes = finalFile.length(),
                    createdAt = now,
                )
                attachmentDao.insert(entity)
                attachmentDao.deleteById(attachmentId)
                entity.toModel()
            }
        } catch (error: Throwable) {
            // The old row and old file stay untouched when the swap did not commit.
            if (attachmentDao.findById(newId) == null) {
                finalFile.delete()
            }
            throw error
        }
        // Old reference is gone; the old file is now unreferenced and may be removed.
        fileFor(old.toModel()).delete()
        replacement
    }

    override suspend fun deleteAttachment(attachmentId: String) = withExclusivePublished {
        val path = database.withDatasetTransaction {
            val existing = attachmentDao.findById(attachmentId)
                ?: throw IllegalArgumentException("截图不存在")
            validateTarget(existing.taskDefinitionId, existing.occurrenceId)
            attachmentDao.deleteById(attachmentId)
            existing.relativePath
        }
        File(attachmentsDirectory(), path).delete()
        Unit
    }

    override fun fileFor(attachment: Attachment): File {
        val directory = attachmentsDirectory()
        val file = File(directory, attachment.relativePath)
        require(file.canonicalPath.startsWith(directory.canonicalPath)) { "截图路径无效" }
        return file
    }

    override suspend fun cleanupOrphans(): Int = withContext(Dispatchers.IO) {
        // The snapshot and every deletion happen under the same lock as publications,
        // so no newly committed file can be missing from the snapshot.
        withExclusivePublished {
            // The reference snapshot is always read under the lock, before the directory
            // check: a publication either commits before this read (and is protected) or
            // waits for the lock. Reading it after an early return would also make the
            // snapshot invisible to the concurrency barrier when nothing was stored yet.
            val referenced = attachmentDao.findAll().map { it.relativePath }.toSet()
            sync.hooks?.afterReferenceSnapshot(referenced)
            val directory = attachmentsDirectory().takeIf { it.isDirectory } ?: return@withExclusivePublished 0
            var deleted = 0
            directory.listFiles()?.forEach { file ->
                val staleTemp = file.name.endsWith(TEMP_SUFFIX) &&
                    System.currentTimeMillis() - file.lastModified() > STALE_TEMP_MILLIS
                val unreferenced = !file.name.endsWith(TEMP_SUFFIX) && file.name !in referenced
                if ((staleTemp || unreferenced) && file.delete()) deleted += 1
            }
            deleted
        }
    }

    private suspend fun validateTarget(taskDefinitionId: String?, occurrenceId: String?) {
        require((taskDefinitionId == null) != (occurrenceId == null)) {
            "截图必须且只能归属到一个任务或一次打卡"
        }
        val taskId = if (taskDefinitionId != null) {
            val task = taskDao.findById(taskDefinitionId)
                ?: throw IllegalArgumentException("任务不存在")
            task.id
        } else {
            val occurrence = occurrenceDao.findById(checkNotNull(occurrenceId))
                ?: throw IllegalArgumentException("打卡记录不存在")
            occurrence.taskDefinitionId
        }
        val task = taskDao.findById(taskId) ?: throw IllegalArgumentException("任务不存在")
        val order = orderDao.findById(task.orderId) ?: throw IllegalArgumentException("订单不存在")
        val account = accountDao.findById(order.accountId)
            ?: throw IllegalArgumentException("游戏账号不存在")
        val owner = ownerDao.findById(account.ownerId) ?: throw IllegalArgumentException("老板不存在")
        require(owner.archivedAt == null) { ARCHIVED_OWNER_ORDER_MESSAGE }
        require(account.archivedAt == null) { ARCHIVED_ACCOUNT_ORDER_MESSAGE }
    }

    private suspend fun stage(uri: Uri): StagedFile = withContext(Dispatchers.IO) {
        require(uri != Uri.EMPTY) { "未选择图片" }
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: "image/jpeg"
        require(mimeType.startsWith("image/")) { "请选择图片文件" }
        val id = UUID.randomUUID().toString()
        val extension = extensionFor(mimeType)
        val directory = attachmentsDirectory().apply { mkdirs() }
        val temp = File(directory, "$id$TEMP_SUFFIX")
        try {
            val input = resolver.openInputStream(uri)
                ?: throw IOException("无法读取所选图片")
            input.use { source ->
                temp.outputStream().use { target -> source.copyTo(target) }
            }
            require(temp.length() > 0) { "所选图片内容为空" }
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
        StagedFile(id = id, mimeType = mimeType, tempFile = temp, extension = extension)
    }

    private suspend fun commit(staged: StagedFile): File = withContext(Dispatchers.IO) {
        val finalFile = File(attachmentsDirectory(), "${staged.id}.${staged.extension}")
        val moved = staged.tempFile.renameTo(finalFile)
        if (!moved) {
            // renameTo can fail across filesystems; copy instead and keep the old temp
            // until the copy succeeds.
            runCatching {
                staged.tempFile.inputStream().use { input ->
                    finalFile.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure { error ->
                finalFile.delete()
                staged.tempFile.delete()
                throw IOException("保存截图失败", error)
            }
        }
        staged.tempFile.delete()
        finalFile
    }

    private fun attachmentsDirectory(): File = File(context.filesDir, ATTACHMENT_DIRECTORY)

    private fun extensionFor(mimeType: String): String = when (mimeType.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic", "image/heif" -> "heic"
        else -> "jpg"
    }

    private companion object {
        const val ATTACHMENT_DIRECTORY = "attachments"
        const val TEMP_SUFFIX = ".tmp"
        const val STALE_TEMP_MILLIS = 60L * 60L * 1000L
    }
}
