package com.kuankuan.daigan.backup

import android.content.Context
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import com.kuankuan.daigan.core.time.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The durable steps of a dataset replacement. Persisted before each destructive step. */
enum class RestorePhase {
    /** Staged data validated; nothing destructive has happened yet. */
    PREPARED,

    /** A complete rollback snapshot of the current dataset was written. */
    ROLLBACK_READY,

    /** The attachment directory was replaced; the database is still the original. */
    FILES_SWAPPED,

    /** The replacement transaction committed. */
    DB_COMMITTED,
}

/**
 * The recovery journal of an in-progress restore.
 *
 * It only holds paths, a random id and a phase — never passwords, credentials or row
 * contents. All paths are relative to the app's private files directory so a journal
 * stays valid across process restarts.
 */
data class RestoreJournal(
    val id: String,
    val phase: RestorePhase,
    val stagingDirectory: String,
    val rollbackDirectory: String,
    val stagingDatabase: String,
    val createdAt: Long,
)

/**
 * Private, app-owned storage used by backup and restore.
 *
 * ```
 * files/attachments/                     live screenshots
 * files/attachments.bak-<restoreId>/     previous screenshots during a replacement
 * files/backup/staging/<workId>/         export archive or extracted import
 * files/backup/restore-journal.json      durable restore state
 * ```
 */
@Singleton
class BackupStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timeProvider: TimeProvider,
) {

    private val json = Json { isLenient = false; ignoreUnknownKeys = false }

    val filesDirectory: File get() = context.filesDir

    val attachmentsDirectory: File get() = File(context.filesDir, ATTACHMENTS)

    private val root: File get() = File(context.filesDir, ROOT)

    private val stagingRoot: File get() = File(root, "staging")

    private val journalFile: File get() = File(root, JOURNAL)

    fun newWorkDirectory(prefix: String): File {
        val directory = File(stagingRoot, "$prefix-${UUID.randomUUID()}")
        check(directory.mkdirs() || directory.isDirectory) { "无法创建临时目录" }
        syncDirectory(stagingRoot)
        syncDirectory(root)
        syncFilesDirectory()
        return directory
    }

    fun resolve(relativePath: String): File {
        val file = File(context.filesDir, relativePath)
        val base = context.filesDir.canonicalFile
        require(file.canonicalPath.startsWith(base.path + File.separator)) {
            "恢复记录的路径无效"
        }
        return file
    }

    fun relativeOf(file: File): String =
        file.relativeToOrNull(context.filesDir)?.path ?: file.name

    fun attachmentsBackupDirectory(restoreId: String): File =
        File(context.filesDir, "attachments.bak-$restoreId")

    fun backupFileName(exportedAt: Long, encrypted: Boolean): String {
        val zone = java.time.ZoneId.systemDefault()
        val stamp = java.time.Instant.ofEpochMilli(exportedAt)
            .atZone(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val suffix = if (encrypted) "-encrypted" else ""
        return "daigan-backup-$stamp$suffix.${BackupFormat.FILE_EXTENSION}"
    }

    fun availableBytes(): Long = runCatching {
        StatFs(context.filesDir.path).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    /** Refuses a restore that cannot fit staging, replacement and rollback copies. */
    fun requireSpaceFor(requiredBytes: Long) {
        val required = requiredBytes.coerceAtLeast(MIN_REQUIRED_BYTES)
        val available = availableBytes()
        if (available < required) {
            throw BackupException(
                "手机可用存储空间不足（需要约 ${required / (1024 * 1024)} MB，" +
                    "当前约 ${available / (1024 * 1024)} MB），请清理后重试。",
            )
        }
    }

    fun writeJournal(journal: RestoreJournal) {
        root.mkdirs()
        val payload = JsonObject(
            linkedMapOf(
                "id" to JsonPrimitive(journal.id),
                "phase" to JsonPrimitive(journal.phase.name),
                "stagingDirectory" to JsonPrimitive(journal.stagingDirectory),
                "rollbackDirectory" to JsonPrimitive(journal.rollbackDirectory),
                "stagingDatabase" to JsonPrimitive(journal.stagingDatabase),
                "createdAt" to JsonPrimitive(journal.createdAt),
            ),
        )
        val text = json.encodeToString(
            kotlinx.serialization.json.JsonElement.serializer(),
            payload,
        )
        val temp = File(root, "$JOURNAL.tmp")
        FileOutputStream(temp).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        // Atomic within the same directory: either the old or the new journal exists.
        if (!temp.renameTo(journalFile)) {
            temp.delete()
            throw BackupException("无法保存恢复进度，已停止操作并保留原数据。")
        }
        syncDirectory(root)
    }

    fun syncFilesDirectory() = syncDirectory(context.filesDir)

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    /** File contents must reach storage before publishing their directory by rename. */
    fun syncTree(directory: File) {
        directory.walkBottomUp().forEach { file ->
            if (file.isDirectory) syncDirectory(file)
            else FileOutputStream(file, true).use { it.fd.sync() }
        }
    }

    /**
     * Returns the journal, or null when no restore is in progress. A journal that cannot
     * be parsed is treated as a corrupt recovery record and fails safe: recovery will
     * restore the previous attachment directory when it still exists.
     */
    fun readJournal(): RestoreJournal? {
        val file = journalFile
        if (!file.isFile) return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val objectValue = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return null
        fun string(key: String): String? =
            (objectValue[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        val id = string("id")?.takeIf {
            runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)
        } ?: return null
        val phase = string("phase")?.let { name ->
            RestorePhase.entries.firstOrNull { it.name == name }
        } ?: return null
        val staging = string("stagingDirectory") ?: return null
        val rollback = string("rollbackDirectory") ?: return null
        if (!Regex("backup/staging/[A-Za-z0-9-]+/staging").matches(staging) ||
            !staging.endsWith("/staging") ||
            !rollback.startsWith("backup/staging/") || !rollback.endsWith("/rollback") ||
            staging.removeSuffix("/staging") != rollback.removeSuffix("/rollback") ||
            runCatching { resolve(staging); resolve(rollback) }.isFailure
        ) return null
        val stagingDatabase = string("stagingDatabase") ?: return null
        if (stagingDatabase != "restore-staging-$id") return null
        return RestoreJournal(
            id = id,
            phase = phase,
            stagingDirectory = staging,
            rollbackDirectory = rollback,
            stagingDatabase = stagingDatabase,
            createdAt = (objectValue["createdAt"] as? JsonPrimitive)
                ?.takeIf { !it.isString }?.content?.toLongOrNull() ?: 0L,
        )
    }

    fun hasJournalFile(): Boolean = journalFile.isFile

    fun clearJournal() {
        if (journalFile.exists() && !journalFile.delete()) {
            throw BackupException("无法清除恢复进度，请重启应用后重试。")
        }
        File(root, "$JOURNAL.tmp").delete()
        if (root.isDirectory) syncDirectory(root)
    }

    fun deleteDirectoryQuietly(directory: File) {
        runCatching {
            if (directory.exists()) directory.deleteRecursively()
        }
    }

    /**
     * Removes staged work that no journal refers to. Called once at startup, before any
     * repository or reminder worker can observe the dataset. An interrupted export or a
     * cancelled import therefore never leaves a half-written file behind.
     */
    fun cleanupStaleStaging(keep: Set<String> = emptySet()) {
        val staging = stagingRoot
        if (!staging.isDirectory) return
        staging.listFiles()?.forEach { directory ->
            if (directory.absolutePath !in keep) deleteDirectoryQuietly(directory)
        }
    }

    /** Previous attachment directories that no in-progress restore needs any more. */
    fun staleAttachmentBackups(keep: String?): List<File> =
        context.filesDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("attachments.bak-") }
            ?.filter { keep == null || it.absolutePath != keep }
            .orEmpty()

    fun now(): Long = timeProvider.now().toEpochMilli()

    private companion object {
        const val ROOT = "backup"
        const val ATTACHMENTS = "attachments"
        const val JOURNAL = "restore-journal.json"

        /** Staging + replacement + rollback headroom. */
        const val MIN_REQUIRED_BYTES = 32L * 1024 * 1024
    }
}
