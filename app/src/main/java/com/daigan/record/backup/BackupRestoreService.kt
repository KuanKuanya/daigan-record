package com.daigan.record.backup

import android.net.Uri
import androidx.room.withTransaction
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.local.RECORD_DATABASE_VERSION
import com.daigan.record.data.repository.AttachmentSync
import com.daigan.record.data.repository.DatasetGate
import com.daigan.record.data.repository.ApplicationReadiness
import com.daigan.record.notifications.ReminderCoordinator
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

/** What can be told about a chosen file before asking for a password. */
data class BackupInspection(
    val encrypted: Boolean,
    val envelopeVersion: Int?,
    val archiveBytes: Long,
    val fileName: String,
)

/** The user-facing summary shown before the replacement is confirmed. */
data class RestorePreview(
    val exportedAt: Instant,
    val schemaVersion: Int,
    val formatVersion: Int,
    val owners: Int,
    val accounts: Int,
    val orders: Int,
    val hostingPeriods: Int,
    val receipts: Int,
    val tasks: Int,
    val occurrences: Int,
    val attachments: Int,
    val attachmentBytes: Long,
    val recordCount: Int,
    val remindersEnabled: Int,
    val empty: Boolean,
)

enum class RecoveryOutcome {
    /** No interrupted restore was found. */
    NONE,

    /** An interrupted replacement was rolled back; the original dataset is intact. */
    ROLLED_BACK,

    /** An interrupted replacement had already committed; the restored dataset is live. */
    COMPLETED_RESTORE,
}

data class RestoreResult(
    val exportedAt: Instant,
    val owners: Int,
    val accounts: Int,
    val orders: Int,
    val receipts: Int,
    val attachments: Int,
    val recordCount: Int,
)

/**
 * Safe full-dataset restore.
 *
 * Flow and guarantees:
 *
 * 1. **Inspect** — the chosen document is copied into private staging with a size bound
 *    and its format/envelope header is validated. Nothing else is read.
 * 2. **Prepare** — decrypt (only when encrypted), extract and validate into an isolated
 *    staging database. Unsupported versions, wrong passwords, broken relationships,
 *    invalid financial values and missing/corrupt screenshots are all rejected here,
 *    before anything destructive is allowed.
 * 3. **Confirm** — the caller shows [RestorePreview]; cancelling leaves the dataset
 *    untouched (only staging files are deleted).
 * 4. **Replace** — a durable journal is written, a complete rollback snapshot is taken,
 *    the attachment directory is swapped, and one database transaction replaces every
 *    durable row. The transaction writes a restore marker as its commit point.
 * 5. **Recover** — [recoverIfNeeded] runs at process start, before repositories or
 *    workers can read data. It finishes a committed replacement or rolls an interrupted
 *    one back, so the app can only ever open the original or the restored dataset.
 */
@Singleton
class BackupRestoreService @Inject constructor(
    private val storage: BackupStorage,
    private val databaseAccess: BackupDatabaseAccess,
    private val snapshotBuilder: BackupSnapshotBuilder,
    private val validator: BackupValidator,
    private val attachmentSync: AttachmentSync,
    private val documentStore: BackupDocumentStore,
    private val timeProvider: TimeProvider,
    private val reminderCoordinator: ReminderCoordinator,
) {

    private class Session(
        val id: String,
        val workDirectory: File,
        val archive: File,
        val inspection: BackupInspection,
        var extracted: ExtractedArchive? = null,
        var dataSet: BackupDataSet? = null,
        var stagingDatabase: String? = null,
    )

    /** Test barriers also support external process-kill probes; unset in production. */
    var transitionHook: (suspend (String) -> Unit)? = null

    private suspend fun checkpoint(name: String) { transitionHook?.invoke(name) }

    private val operationMutex = Mutex()
    private var session: Session? = null
    private var applying = false

    val hasPendingSession: Boolean get() = session != null

    suspend fun inspect(uri: Uri): BackupInspection = operationMutex.withLock {
        if (storage.hasJournalFile()) {
            throw BackupException("上次恢复尚未完成，请重启应用完成恢复后再选择文件。")
        }
        discardSessionInternal()
        val id = UUID.randomUUID().toString()
        val work = storage.newWorkDirectory("restore-$id")
        val archive = File(work, "document.dgbk")
        try {
            val bytes = documentStore.copyDocumentToFile(
                uri = uri,
                target = archive,
                maxBytes = BackupFormat.Limits.MAX_ARCHIVE_BYTES,
            )
            val header = BackupCrypto.readHeader(archive)
            val inspection = BackupInspection(
                encrypted = header != null,
                envelopeVersion = header?.envelopeVersion,
                archiveBytes = bytes,
                fileName = uri.lastPathSegment?.substringAfterLast('/').orEmpty(),
            )
            session = Session(
                id = id,
                workDirectory = work,
                archive = archive,
                inspection = inspection,
            )
            inspection
        } catch (error: Throwable) {
            storage.deleteDirectoryQuietly(work)
            throw error
        }
    }

    /**
     * Decrypts (when needed), extracts and validates into isolated staging. The password
     * array is cleared by the crypto layer. A failure discards the session and leaves the
     * live dataset untouched.
     */
    suspend fun prepare(password: CharArray?): RestorePreview = operationMutex.withLock {
        val active = session ?: throw BackupException("请先选择备份文件。")
        try {
            withContext(Dispatchers.IO) { prepareInternal(active, password) }
        } catch (error: Throwable) {
            discardSessionInternal()
            throw error
        } finally {
            password?.fill('\u0000')
        }
    }

    private suspend fun prepareInternal(active: Session, password: CharArray?): RestorePreview {
        val plain = if (active.inspection.encrypted) {
            val supplied = password
                ?: throw BackupException("该备份已加密，请输入备份密码。")
            if (supplied.isEmpty()) throw BackupException("请输入备份密码。")
            val target = File(active.workDirectory, "archive.zip")
            FileOutputStream(target).use { output ->
                BackupCrypto.decryptTo(active.archive, output, supplied)
            }
            if (!BackupCrypto.isZip(target)) {
                throw BackupException("密码不正确，或备份文件已被修改。")
            }
            target
        } else {
            active.archive
        }
        try {
            val extracted = BackupArchiveReader.read(
                file = plain,
                stagingDirectory = File(active.workDirectory, "staging"),
                currentSchemaVersion = RECORD_DATABASE_VERSION,
            )
            storage.requireSpaceFor(
                extracted.uncompressedBytes * 2 + active.archive.length() * 2,
            )
            val dataSet = BackupDataCodec.decode(
                extracted.dataBytes.toString(Charsets.UTF_8),
            )
            verifyCounts(dataSet, extracted.manifest)

            val stagingName = "restore-staging-${active.id}"
            active.stagingDatabase = stagingName
            deleteStagingDatabase(stagingName)
            val staging = databaseAccess.openStagingDatabase(stagingName)
            try {
                staging.withTransaction {
                    databaseAccess.insertAll(staging.openHelper.writableDatabase, dataSet)
                }
                validator.validate(staging, dataSet, extracted)
            } finally {
                staging.close()
            }
            deleteStagingDatabase(stagingName)

            storage.syncTree(extracted.attachmentsDirectory)
            active.extracted = extracted
            active.dataSet = dataSet
            active.stagingDatabase = stagingName
            return buildPreview(dataSet, extracted.manifest)
        } finally {
            // The decrypted copy is not needed after extraction and must not linger.
            if (plain != active.archive) plain.delete()
        }
    }

    /**
     * Replaces the dataset after an explicit in-app confirmation. Returns only after the
     * replacement is fully committed; a failure before the commit leaves the original
     * dataset intact.
     */
    suspend fun applyConfirmed(): RestoreResult = operationMutex.withLock {
        val active = session ?: throw BackupException("请先选择并校验备份文件。")
        val dataSet = active.dataSet ?: throw BackupException("备份尚未校验完成。")
        val extracted = active.extracted ?: throw BackupException("备份尚未校验完成。")
        check(!applying) { "正在恢复，请稍候。" }
        applying = true
        try {
            val stagingDirectory = File(active.workDirectory, "staging")
            val rollbackDirectory = File(active.workDirectory, "rollback")
            val journal = RestoreJournal(
                id = active.id,
                phase = RestorePhase.PREPARED,
                stagingDirectory = storage.relativeOf(stagingDirectory),
                rollbackDirectory = storage.relativeOf(rollbackDirectory),
                stagingDatabase = active.stagingDatabase.orEmpty(),
                createdAt = timeProvider.now().toEpochMilli(),
            )
            val attachmentsBackup = storage.attachmentsBackupDirectory(active.id)

            // Cancellation cannot release the dataset gate between the file swap and
            // transaction outcome. Startup uses the same durable marker after process death.
            withContext(Dispatchers.IO + NonCancellable) {
                DatasetGate.withExclusiveDatasetReplacement {
                    attachmentSync.withExclusive {
                        var databaseCommitted = false
                        try {
                            storage.requireSpaceFor(extracted.uncompressedBytes * 2 +
                                storage.attachmentsDirectory.walkTopDown()
                                    .filter { it.isFile }.sumOf { it.length() } * 2)
                            storage.writeJournal(journal)
                            checkpoint("PREPARED")
                            storage.deleteDirectoryQuietly(rollbackDirectory)
                            check(rollbackDirectory.mkdirs() || rollbackDirectory.isDirectory)
                            snapshotBuilder.build(rollbackDirectory, timeProvider.now(), null, "rollback")
                            storage.syncTree(rollbackDirectory)
                            storage.syncTree(active.workDirectory)
                            storage.writeJournal(journal.copy(phase = RestorePhase.ROLLBACK_READY))
                            checkpoint("ROLLBACK_READY")

                            val live = storage.attachmentsDirectory
                            // Always preserve an original directory, even for an empty
                            // installation. Absence can never be confused with rollback done.
                            check(live.mkdirs() || live.isDirectory)
                            if (attachmentsBackup.exists() || !live.renameTo(attachmentsBackup)) {
                                throw BackupException("无法准备替换截图目录，已停止恢复。")
                            }
                            storage.syncFilesDirectory()
                            checkpoint("ORIGINAL_FILES_MOVED")
                            val staged = File(stagingDirectory, "attachments")
                            if (!staged.renameTo(live)) {
                                throw BackupException("无法替换截图目录，已停止恢复。")
                            }
                            storage.syncFilesDirectory()
                            storage.writeJournal(journal.copy(phase = RestorePhase.FILES_SWAPPED))
                            checkpoint("FILES_SWAPPED")
                            databaseAccess.replaceAll(dataSet, restoreId = active.id)
                            databaseCommitted = true
                            DatasetGate.datasetReplaced()
                            checkpoint("DATABASE_COMMITTED")
                            storage.writeJournal(journal.copy(phase = RestorePhase.DB_COMMITTED))
                            checkpoint("DB_COMMITTED")
                            // Reconcile persisted work before deleting recovery evidence.
                            // This remains inside NonCancellable and the dataset permit.
                            reminderCoordinator.onDatasetReplaced()
                            finishForward(active.id)
                        } catch (error: Throwable) {
                            try {
                                if (databaseCommitted || databaseAccess.currentRestoreMarker() == active.id) {
                                    // Never swap old files back after the commit point.
                                    try {
                                        reminderCoordinator.onDatasetReplaced()
                                        finishForward(active.id)
                                    } catch (cleanupError: Throwable) {
                                        throw BackupException("资料已恢复，但清理未完成。请重启应用以完成恢复。", cleanupError)
                                    }
                                    throw BackupException("资料已恢复，但后续处理被中断。请重启应用刷新提醒与页面。", error)
                                } else {
                                    rollbackFiles(attachmentsBackup)
                                    storage.clearJournal()
                                }
                            } catch (recoveryError: Throwable) {
                                if (storage.hasJournalFile()) {
                                    // Set this before the exclusive gate is released.
                                    DatasetGate.requireRecovery()
                                }
                                throw recoveryError
                            }
                            throw error
                        }
                    }
                }
                storage.deleteDirectoryQuietly(active.workDirectory)
            }

            session = null
            RestoreResult(
                exportedAt = Instant.ofEpochMilli(extracted.manifest.exportedAt),
                owners = dataSet.count("owners"),
                accounts = dataSet.count("game_accounts"),
                orders = dataSet.count("orders"),
                receipts = dataSet.count("receipts"),
                attachments = dataSet.count("attachments"),
                recordCount = dataSet.tables.sumOf { it.rows.size },
            )
        } catch (error: Throwable) {
            if (storage.hasJournalFile()) {
                DatasetGate.requireRecovery()
                ApplicationReadiness.fail()
            }
            discardSessionInternal()
            throw error
        } finally {
            applying = false
        }
    }

    /** Deleting a staged import must not touch live data. */
    suspend fun discardSession() = operationMutex.withLock {
        discardSessionInternal()
    }

    /**
     * Startup recovery. Runs before any repository or reminder worker can read data.
     * Returns which complete dataset is now live.
     */
    suspend fun recoverIfNeeded(): RecoveryOutcome {
        val outcome = try {
            operationMutex.withLock {
                withContext(Dispatchers.IO + NonCancellable) { recoverInternal() }
            }
        } catch (error: Throwable) {
            DatasetGate.requireRecovery()
            ApplicationReadiness.fail()
            throw error
        }
        DatasetGate.recoveryFinished()
        return outcome
    }

    private suspend fun recoverInternal(): RecoveryOutcome {
        val journal = storage.readJournal()
        if (journal == null && storage.hasJournalFile()) {
            // Guessing which directory to retain can combine committed new rows with
            // old screenshots. Keep all evidence and block access for explicit retry.
            throw BackupException("恢复进度文件损坏，已保留全部数据并停止打开资料。请保留应用数据以便修复。")
        }
        if (journal == null) {
            // No journal: any leftover marker is stale, and backup directories whose
            // journal is gone belong to a completed replacement.
            if (databaseAccess.currentRestoreMarker() != null) {
                DatasetGate.withExclusiveDatasetReplacement {
                    reminderCoordinator.onDatasetReplaced()
                    databaseAccess.clearRestoreMarker()
                }
            }
            storage.staleAttachmentBackups(keep = null).forEach { directory ->
                if (storage.attachmentsDirectory.isDirectory) {
                    storage.deleteDirectoryQuietly(directory)
                }
            }
            storage.cleanupStaleStaging()
            return RecoveryOutcome.NONE
        }

        return DatasetGate.withExclusiveDatasetReplacement {
            withContext(Dispatchers.IO) {
                attachmentSync.withExclusive {
                    val stagingDir = runCatching { storage.resolve(journal.stagingDirectory) }.getOrNull()
                    val rollbackDir = runCatching { storage.resolve(journal.rollbackDirectory) }.getOrNull()
                    val attachmentsBackup = storage.attachmentsBackupDirectory(journal.id)
                    val committed = databaseAccess.currentRestoreMarker() == journal.id

                    if (committed) {
                        DatasetGate.datasetReplaced()
                        // Failure leaves journal + marker intact for the next retry.
                        reminderCoordinator.onDatasetReplaced()
                        storage.deleteDirectoryQuietly(attachmentsBackup)
                        stagingDir?.let(storage::deleteDirectoryQuietly)
                        rollbackDir?.let(storage::deleteDirectoryQuietly)
                        deleteStagingDatabase(journal.stagingDatabase)
                        storage.clearJournal()
                        databaseAccess.clearRestoreMarker()
                        storage.cleanupStaleStaging()
                        RecoveryOutcome.COMPLETED_RESTORE
                    } else {
                        rollbackFiles(attachmentsBackup)
                        stagingDir?.let(storage::deleteDirectoryQuietly)
                        rollbackDir?.let(storage::deleteDirectoryQuietly)
                        deleteStagingDatabase(journal.stagingDatabase)
                        storage.clearJournal()
                        databaseAccess.clearRestoreMarker()
                        storage.cleanupStaleStaging()
                        RecoveryOutcome.ROLLED_BACK
                    }
                }
            }
        }
    }

    /**
     * Puts the previous attachment directory back. Idempotent: if the backup directory
     * no longer exists, the live directory is already the original one.
     */
    private fun rollbackFiles(attachmentsBackup: File) {
        if (!attachmentsBackup.exists()) return
        val live = storage.attachmentsDirectory
        if (live.exists() && !live.deleteRecursively()) {
            throw BackupException("无法移除未提交的截图目录，已保留恢复记录。")
        }
        if (!attachmentsBackup.renameTo(live)) {
            throw BackupException("无法恢复原截图目录，请重启应用后重试。")
        }
        storage.syncFilesDirectory()
    }

    private suspend fun finishForward(restoreId: String) {
        val journal = storage.readJournal()
        // The new DB + files are complete. Remove journal durably before clearing the
        // marker; either interruption then identifies a complete committed dataset.
        storage.clearJournal()
        databaseAccess.clearRestoreMarker()
        storage.deleteDirectoryQuietly(storage.attachmentsBackupDirectory(restoreId))
        if (journal != null && journal.id == restoreId) {
            storage.deleteDirectoryQuietly(storage.resolve(journal.stagingDirectory))
            storage.deleteDirectoryQuietly(storage.resolve(journal.rollbackDirectory))
            deleteStagingDatabase(journal.stagingDatabase)
        }
    }

    private fun discardSessionInternal() {
        val active = session ?: return
        session = null
        active.stagingDatabase?.let(::deleteStagingDatabase)
        // An active journal owns this directory until recovery has finished.
        if (!storage.hasJournalFile()) storage.deleteDirectoryQuietly(active.workDirectory)
    }

    private fun deleteStagingDatabase(name: String) {
        if (name.isBlank()) return
        storageStagingFiles(name).forEach { file ->
            runCatching { file.delete() }
        }
    }

    private fun storageStagingFiles(name: String): List<File> =
        databaseAccess.stagingFiles(name)

    private fun verifyCounts(dataSet: BackupDataSet, manifest: BackupManifest) {
        manifest.tableCounts.forEach { (table, declared) ->
            val actual = dataSet.count(table)
            if (actual != declared) {
                throw BackupException("备份中的「$table」记录数量与清单不一致，无法恢复。")
            }
        }
    }

    private fun buildPreview(
        dataSet: BackupDataSet,
        manifest: BackupManifest,
    ): RestorePreview {
        val reminderRows = dataSet.rows("reminder_settings")
        val enabled = reminderRows.firstOrNull()?.let { row ->
            val spec = checkNotNull(BackupTableCatalog.table("reminder_settings"))
            fun enabledAt(name: String): Boolean {
                val index = spec.columnNames.indexOf(name)
                return (row.getOrNull(index) as? JsonPrimitive)?.content == "1"
            }
            listOf(
                enabledAt("shortDeadlineEnabled"),
                enabledAt("hostingExpiryEnabled"),
                enabledAt("unfinishedTasksEnabled"),
            ).count { it }
        } ?: 0
        val recordCount = dataSet.tables.sumOf { it.rows.size }
        return RestorePreview(
            exportedAt = Instant.ofEpochMilli(manifest.exportedAt),
            schemaVersion = manifest.schemaVersion,
            formatVersion = manifest.formatVersion,
            owners = dataSet.count("owners"),
            accounts = dataSet.count("game_accounts"),
            orders = dataSet.count("orders"),
            hostingPeriods = dataSet.count("hosting_periods"),
            receipts = dataSet.count("receipts"),
            tasks = dataSet.count("task_definitions"),
            occurrences = dataSet.count("task_occurrences"),
            attachments = dataSet.count("attachments"),
            attachmentBytes = manifest.attachmentTotalBytes,
            recordCount = recordCount,
            remindersEnabled = enabled,
            empty = recordCount == 0,
        )
    }
}
