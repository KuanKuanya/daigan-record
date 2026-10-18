package com.daigan.record.data.template

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A user-facing reason why a document-picker stream could not be used. */
class TemplateFileIoException(message: String) : IOException(message)

/**
 * Reads and writes one template document through Android's Storage Access Framework.
 *
 * No broad storage permission is requested and no network or cloud service is involved:
 * the caller supplies the URI returned by the system picker. The ViewModel never touches
 * a stream directly, and nothing here logs document content.
 */
interface TemplateFileRepository {

    /** Reads at most [maxBytes]; a larger document fails instead of being buffered. */
    suspend fun read(uri: Uri, maxBytes: Int = TemplateFileCodec.MAX_FILE_BYTES): ByteArray

    suspend fun write(uri: Uri, bytes: ByteArray)
}

@Singleton
class ContentResolverTemplateFileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : TemplateFileRepository {

    override suspend fun read(uri: Uri, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val stream = runCatching { context.contentResolver.openInputStream(uri) }
            .getOrNull()
            ?: throw TemplateFileIoException("无法读取所选文件，请确认文件仍然可用")
        stream.use { input ->
            val buffer = ByteArray(8 * 1024)
            val output = java.io.ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > maxBytes) {
                    throw TemplateFileIoException(
                        "文件超过 ${maxBytes / 1024} KB 上限，未导入任何内容",
                    )
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    override suspend fun write(uri: Uri, bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            val stream = runCatching { context.contentResolver.openOutputStream(uri, "wt") }
                .getOrNull()
                ?: throw TemplateFileIoException("无法写入所选位置，请换一个文件夹后重试")
            stream.use { it.write(bytes) }
        }
    }
}
