package com.daigan.record.backup

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Streams a backup document through the system document picker.
 *
 * No broad storage permission is requested and no network is involved: only the URI
 * returned by the picker is used. Failures are mapped to clear Chinese messages; a
 * cancelled picker is reported by the caller and never becomes a "successful" export.
 */
interface BackupDocumentStore {

    /** Copies [uri] into [target], rejecting a document larger than [maxBytes]. */
    suspend fun copyDocumentToFile(uri: Uri, target: File, maxBytes: Long): Long

    /** Writes [source] to [uri], truncating any previous content. */
    suspend fun writeFileToDocument(uri: Uri, source: File)

    /** Actual provider name, which may differ from the picker suggestion. */
    fun displayName(uri: Uri): String? = null

    fun hasRoomFor(bytes: Long): Boolean
}

@Singleton
class ContentResolverBackupDocumentStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : BackupDocumentStore {

    override suspend fun copyDocumentToFile(uri: Uri, target: File, maxBytes: Long): Long =
        withContext(Dispatchers.IO) {
            if (uri == Uri.EMPTY) throw BackupException("未选择备份文件。")
            val input = runCatching { context.contentResolver.openInputStream(uri) }
                .getOrNull()
                ?: throw BackupException("无法读取所选文件，请确认文件仍然可用。")
            input.use { source ->
                target.parentFile?.mkdirs()
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = try {
                            source.read(buffer)
                        } catch (error: IOException) {
                            throw BackupException("读取备份文件时中断，未改动任何数据。", error)
                        }
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) {
                            throw BackupException("所选文件超过备份大小上限，已停止读取。")
                        }
                        output.write(buffer, 0, read)
                    }
                    if (total == 0L) throw BackupException("所选文件是空文件，无法恢复。")
                    total
                }
            }
        }

    override suspend fun writeFileToDocument(uri: Uri, source: File) = withContext(Dispatchers.IO) {
        if (uri == Uri.EMPTY) throw BackupException("未选择保存位置。")
        // Document providers may report a writable directory but fail on write; both
        // cases are reported instead of claiming success.
        if (!hasRoomFor(source.length())) {
            throw BackupException("手机可用存储空间不足，无法写入备份文件。")
        }
        val stream = runCatching { context.contentResolver.openOutputStream(uri, "wt") }
            .getOrNull()
            ?: throw BackupException("无法写入所选位置，请换一个文件夹后重试。")
        stream.use { output ->
            try {
                source.inputStream().buffered().use { input ->
                    input.copyTo(output, 64 * 1024)
                }
                output.flush()
            } catch (error: IOException) {
                throw BackupException("写入备份文件失败，未报告成功。请换一个位置重试。", error)
            }
        }
    }

    override fun displayName(uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return@runCatching uri.path?.let { File(it).name }
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()

    override fun hasRoomFor(bytes: Long): Boolean = runCatching {
        StatFs(context.filesDir.path).availableBytes > bytes + 8L * 1024 * 1024
    }.getOrDefault(true)
}
