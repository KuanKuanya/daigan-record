package com.kuankuan.daigan.backup

import com.kuankuan.daigan.data.local.RECORD_DATABASE_VERSION
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** A complete, self-describing snapshot written into a private staging directory. */
data class BuiltSnapshot(
    val file: File,
    val recordCount: Int,
    val attachmentCount: Int,
    val attachmentBytes: Long,
    val tableCounts: Map<String, Int>,
    val encrypted: Boolean,
)

/**
 * Builds a whole-dataset archive. Used by the user-facing export and by the rollback
 * snapshot that a restore takes before it replaces anything.
 *
 * The caller must already hold the dataset gate and the attachment publication lock;
 * this class only reads and writes private staging files, so both callers share one
 * consistency contract instead of two slightly different ones.
 */
@Singleton
class BackupSnapshotBuilder @Inject constructor(
    private val storage: BackupStorage,
    private val databaseAccess: BackupDatabaseAccess,
) {

    suspend fun build(
        workDirectory: File,
        exportedAt: Instant,
        password: CharArray?,
        baseName: String = "snapshot",
    ): BuiltSnapshot {
        val dataSet = databaseAccess.dump()
        val attachments = collectAttachments(dataSet)
        val dataBytes = BackupDataCodec.encode(dataSet).toByteArray(Charsets.UTF_8)
        val tableCounts = dataSet.tables.associate { it.table.name to it.rows.size }
        val totalAttachmentBytes = attachments.sumOf { it.sizeBytes }
        val manifest = BackupManifest(
            formatVersion = BackupFormat.FORMAT_VERSION,
            schemaVersion = RECORD_DATABASE_VERSION,
            exportedAt = exportedAt.toEpochMilli(),
            generator = BackupFormat.GENERATOR,
            dataSha256 = Sha256.of(dataBytes),
            tableCounts = tableCounts,
            attachments = attachments,
            attachmentTotalBytes = totalAttachmentBytes,
        )
        // Refuse exports that this app could never restore under its resource limits.
        BackupManifestCodec.decode(BackupManifestCodec.encode(manifest), RECORD_DATABASE_VERSION)
        val manifestBytes = BackupManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8)
        if (dataBytes.size > BackupFormat.Limits.MAX_DATA_BYTES ||
            manifestBytes.size > BackupFormat.Limits.MAX_MANIFEST_BYTES
        ) throw BackupException("资料超过单个备份支持的大小，已停止导出。")

        val plain = File(workDirectory, "$baseName.zip")
        FileOutputStream(plain).use { output ->
            BackupArchiveWriter.write(
                out = output,
                manifestJson = manifestBytes,
                dataBytes = dataBytes,
                attachments = attachments.map { entry ->
                    entry.relativePath to File(storage.attachmentsDirectory, entry.relativePath)
                },
                exportedAt = exportedAt.toEpochMilli(),
            )
            output.fd.sync()
        }
        if (plain.length() > BackupFormat.Limits.MAX_ARCHIVE_BYTES - BackupCrypto.HEADER_BYTES - 16) {
            throw BackupException("备份文件超过大小上限，已停止导出。")
        }
        val expandedBytes = dataBytes.size.toLong() + manifestBytes.size + totalAttachmentBytes
        if (expandedBytes > 8L * 1024 * 1024 &&
            expandedBytes / plain.length().coerceAtLeast(1) > BackupFormat.Limits.MAX_EXPANSION_RATIO
        ) throw BackupException("资料压缩比例超过备份格式支持范围，已停止导出。")
        val encrypted = password != null && password.isNotEmpty()
        if (!encrypted) {
            return BuiltSnapshot(
                file = plain,
                recordCount = tableCounts.values.sum(),
                attachmentCount = attachments.size,
                attachmentBytes = totalAttachmentBytes,
                tableCounts = tableCounts,
                encrypted = false,
            )
        }
        val encryptedFile = File(workDirectory, "$baseName.dgbk")
        FileOutputStream(encryptedFile).use { output ->
            BackupCrypto.encryptTo(plain, output, checkNotNull(password))
        }
        plain.delete()
        return BuiltSnapshot(
            file = encryptedFile,
            recordCount = tableCounts.values.sum(),
            attachmentCount = attachments.size,
            attachmentBytes = totalAttachmentBytes,
            tableCounts = tableCounts,
            encrypted = true,
        )
    }

    /**
     * Every attachment row needs its file, with the recorded size. A missing or changed
     * file aborts the snapshot instead of producing an archive that claims to be complete.
     */
    private fun collectAttachments(dataSet: BackupDataSet): List<BackupAttachmentEntry> =
        dataSet.attachmentRows().map { row ->
            if (!SafeNames.isSafeAttachmentName(row.relativePath)) {
                throw BackupException("截图文件名无效，已停止导出。")
            }
            val file = File(storage.attachmentsDirectory, row.relativePath)
            if (file.canonicalFile.parentFile != storage.attachmentsDirectory.canonicalFile) {
                throw BackupException("截图文件路径无效，已停止导出。")
            }
            if (!file.isFile) {
                throw BackupException(
                    "截图文件「${row.relativePath}」缺失，已停止导出。请先删除该截图后再备份。",
                )
            }
            val actualSize = file.length()
            if (actualSize != row.sizeBytes || actualSize <= 0) {
                throw BackupException(
                    "截图文件「${row.relativePath}」大小与记录不一致，已停止导出。",
                )
            }
            BackupAttachmentEntry(
                id = row.id,
                relativePath = row.relativePath,
                mimeType = row.mimeType.ifBlank { "image/jpeg" },
                sizeBytes = actualSize,
                sha256 = Sha256.of(file),
            )
        }
}
