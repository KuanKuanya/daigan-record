package com.daigan.record.backup

import android.net.Uri
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.repository.AttachmentSync
import com.daigan.record.data.repository.DatasetGate
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BackupExportSummary(
    val fileName: String,
    val recordCount: Int,
    val attachmentCount: Int,
    val attachmentBytes: Long,
    val archiveBytes: Long,
    val encrypted: Boolean,
    val exportedAt: Instant,
    val tableCounts: Map<String, Int>,
)

/**
 * Full-dataset export.
 *
 * Consistency strategy: the whole snapshot is built inside the dataset gate, so no
 * business write can interleave, and inside the attachment publication lock, so no
 * screenshot can be added, replaced, deleted or garbage-collected while the archive is
 * built. Rows are read in one SQLite transaction. Database rows and files therefore come
 * from one business state, and a concurrent edit happens strictly before or strictly
 * after the snapshot.
 *
 * The destination document is only written after the archive is complete in private
 * staging storage. Cancellation or failure deletes staging and leaves live data
 * untouched; only a completed write reports success.
 */
@Singleton
class BackupExportService @Inject constructor(
    private val storage: BackupStorage,
    private val snapshotBuilder: BackupSnapshotBuilder,
    private val attachmentSync: AttachmentSync,
    private val documentStore: BackupDocumentStore,
    private val timeProvider: TimeProvider,
) {

    suspend fun export(uri: Uri, password: CharArray?): BackupExportSummary =
        withContext(Dispatchers.IO) {
            val exportedAt = timeProvider.now()
            val work = storage.newWorkDirectory("export")
            try {
                val snapshot = DatasetGate.withExclusiveDatasetAccess {
                    attachmentSync.withExclusive {
                        snapshotBuilder.build(
                            workDirectory = work,
                            exportedAt = exportedAt,
                            password = password,
                        )
                    }
                }
                documentStore.writeFileToDocument(uri, snapshot.file)
                BackupExportSummary(
                    fileName = documentStore.displayName(uri) ?: "已保存到所选位置",
                    recordCount = snapshot.recordCount,
                    attachmentCount = snapshot.attachmentCount,
                    attachmentBytes = snapshot.attachmentBytes,
                    archiveBytes = snapshot.file.length(),
                    encrypted = snapshot.encrypted,
                    exportedAt = exportedAt,
                    tableCounts = snapshot.tableCounts,
                )
            } finally {
                password?.fill('\u0000')
                storage.deleteDirectoryQuietly(work)
            }
        }
}
