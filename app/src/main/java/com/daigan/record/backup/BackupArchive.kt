package com.daigan.record.backup

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Writes the plain ZIP archive described in [BackupFormat].
 *
 * The manifest must already describe [dataBytes] and [attachments]; this writer only
 * serialises the agreed structure. Attachments are written last and in manifest order.
 */
object BackupArchiveWriter {

    fun write(
        out: OutputStream,
        manifestJson: ByteArray,
        dataBytes: ByteArray,
        attachments: List<Pair<String, File>>,
        exportedAt: Long,
    ) {
        val zip = ZipOutputStream(BufferedOutputStream(out, 64 * 1024))
        zip.putNextEntry(entry(BackupFormat.MANIFEST_ENTRY, manifestJson.size.toLong(), exportedAt))
        zip.write(manifestJson)
        zip.closeEntry()

        zip.putNextEntry(entry(BackupFormat.DATA_ENTRY, dataBytes.size.toLong(), exportedAt))
        zip.write(dataBytes)
        zip.closeEntry()

        attachments.forEach { (relativePath, file) ->
            zip.putNextEntry(
                entry(
                    BackupFormat.ATTACHMENT_PREFIX + relativePath,
                    file.length(),
                    exportedAt,
                ),
            )
            file.inputStream().buffered().use { input -> input.copyTo(zip, 64 * 1024) }
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }

    private fun entry(name: String, size: Long, time: Long): ZipEntry =
        ZipEntry(name).apply {
            this.size = size
            this.time = time
            method = ZipEntry.DEFLATED
        }
}

/** Result of extracting a validated archive into isolated staging storage. */
data class ExtractedArchive(
    val manifest: BackupManifest,
    val dataBytes: ByteArray,
    val manifestBytes: ByteArray,
    val attachmentsDirectory: File,
    val uncompressedBytes: Long,
)

/**
 * Reads a plain archive into a staging directory. Every check happens before the caller
 * can touch live data: name safety, duplicates, entry types, declared and actual sizes,
 * checksums, format compatibility and expansion limits.
 */
object BackupArchiveReader {

    /** ZipInputStream alone accepts a truncated archive with no central directory. */
    fun read(file: File, stagingDirectory: File, currentSchemaVersion: Int): ExtractedArchive {
        try {
            java.util.zip.ZipFile(file).use { zip ->
                if (zip.size() > BackupFormat.Limits.MAX_ENTRIES) {
                    throw BackupException("备份包含的条目过多，无法恢复。")
                }
            }
        } catch (error: java.util.zip.ZipException) {
            throw BackupException("备份压缩文件不完整或已损坏，无法恢复。", error)
        }
        return file.inputStream().use { read(it, stagingDirectory, currentSchemaVersion) }
    }

    fun read(
        input: InputStream,
        stagingDirectory: File,
        currentSchemaVersion: Int,
    ): ExtractedArchive {
        require(stagingDirectory.mkdirs() || stagingDirectory.isDirectory) {
            "无法创建恢复临时目录"
        }
        val attachmentsDirectory = File(stagingDirectory, "attachments")
        check(attachmentsDirectory.mkdirs() || attachmentsDirectory.isDirectory) {
            "无法创建恢复临时目录"
        }
        val limiter = BoundedInputStream(input, BackupFormat.Limits.MAX_ARCHIVE_BYTES)
        val counting = CountingInputStream(limiter)
        val zip = ZipInputStream(BufferedInputStream(counting, 64 * 1024))

        var manifest: BackupManifest? = null
        var manifestBytes: ByteArray? = null
        var dataBytes: ByteArray? = null
        val seenNames = HashSet<String>()
        val seenAttachments = HashSet<String>()
        var entries = 0
        var uncompressedTotal = 0L

        while (true) {
            val entry = try {
                zip.nextEntry
            } catch (error: BackupException) {
                throw error
            } catch (error: java.util.zip.ZipException) {
                throw BackupException("备份文件已损坏或不是有效的压缩文件，无法恢复。", error)
            } ?: break

            entries += 1
            if (entries > BackupFormat.Limits.MAX_ENTRIES) {
                throw BackupException("备份包含的条目过多，无法恢复。")
            }
            val name = entry.name
            if (entry.isDirectory) {
                throw BackupException("备份包含不支持的目录条目，无法恢复。")
            }
            if (!SafeNames.isSafeEntryName(name)) {
                throw BackupException("备份包含不安全或不受支持的文件路径，无法恢复。")
            }
            if (!seenNames.add(name)) {
                throw BackupException("备份中重复出现条目「$name」，无法恢复。")
            }

            when {
                name == BackupFormat.MANIFEST_ENTRY -> {
                    if (entries != 1) {
                        throw BackupException("备份清单不在文件开头，无法恢复。")
                    }
                    requireDeclaredSize(entry, BackupFormat.Limits.MAX_MANIFEST_BYTES.toLong(), name)
                    val bytes = readLimited(zip, BackupFormat.Limits.MAX_MANIFEST_BYTES)
                    uncompressedTotal += bytes.size
                    manifestBytes = bytes
                    manifest = BackupManifestCodec.decode(
                        text = bytes.toString(Charsets.UTF_8),
                        currentSchemaVersion = currentSchemaVersion,
                    )
                }

                name == BackupFormat.DATA_ENTRY -> {
                    val parsed = manifest
                        ?: throw BackupException("备份清单不在文件开头，无法恢复。")
                    requireDeclaredSize(entry, BackupFormat.Limits.MAX_DATA_BYTES.toLong(), name)
                    val bytes = readLimited(zip, BackupFormat.Limits.MAX_DATA_BYTES)
                    uncompressedTotal += bytes.size
                    if (!Sha256.of(bytes).equals(parsed.dataSha256, ignoreCase = true)) {
                        throw BackupException("备份的数据校验失败，文件可能已损坏。")
                    }
                    dataBytes = bytes
                }

                name.startsWith(BackupFormat.ATTACHMENT_PREFIX) -> {
                    val parsed = manifest
                        ?: throw BackupException("备份清单不在文件开头，无法恢复。")
                    val relativePath = name.removePrefix(BackupFormat.ATTACHMENT_PREFIX)
                    if (!SafeNames.isSafeAttachmentName(relativePath)) {
                        throw BackupException("备份包含不安全的截图文件名，无法恢复。")
                    }
                    val declared = parsed.attachments.firstOrNull {
                        it.relativePath == relativePath
                    } ?: throw BackupException("备份包含未登记的截图文件，无法恢复。")
                    if (!seenAttachments.add(relativePath)) {
                        throw BackupException("备份中重复出现截图文件「$relativePath」，无法恢复。")
                    }
                    requireDeclaredSize(entry, declared.sizeBytes, name)
                    val target = File(attachmentsDirectory, relativePath)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var written = 0L
                    target.outputStream().buffered().use { output ->
                        val sink = DigestOutputStream(output, digest)
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            written += read
                            uncompressedTotal += read
                            if (uncompressedTotal > BackupFormat.Limits.MAX_ATTACHMENT_TOTAL_BYTES +
                                BackupFormat.Limits.MAX_DATA_BYTES + BackupFormat.Limits.MAX_MANIFEST_BYTES
                            ) throw BackupException("备份解压后的总大小超过上限，无法恢复。")
                            if (written > declared.sizeBytes ||
                                written > BackupFormat.Limits.MAX_ATTACHMENT_BYTES
                            ) {
                                throw BackupException(
                                    "备份中的截图「$relativePath」大小与清单不一致，无法恢复。",
                                )
                            }
                            sink.write(buffer, 0, read)
                        }
                        sink.flush()
                    }
                    if (written != declared.sizeBytes) {
                        throw BackupException(
                            "备份中的截图「$relativePath」大小与清单不一致，无法恢复。",
                        )
                    }
                    if (!Sha256.hex(digest.digest()).equals(declared.sha256, ignoreCase = true)) {
                        throw BackupException("备份中的截图「$relativePath」校验失败，文件可能已损坏。")
                    }
                }

                else -> throw BackupException("备份包含不受支持的条目「$name」，无法恢复。")
            }
            zip.closeEntry()
        }

        val parsedManifest = manifest
            ?: throw BackupException("备份缺少清单文件，无法恢复。")
        val parsedData = dataBytes
            ?: throw BackupException("备份缺少数据文件，无法恢复。")
        val missing = parsedManifest.attachments
            .map { it.relativePath }
            .filterNot { it in seenAttachments }
        if (missing.isNotEmpty()) {
            throw BackupException("备份缺少截图文件「${missing.first()}」，无法恢复。")
        }
        if (uncompressedTotal > BackupFormat.Limits.MAX_ATTACHMENT_TOTAL_BYTES +
            BackupFormat.Limits.MAX_DATA_BYTES + BackupFormat.Limits.MAX_MANIFEST_BYTES
        ) {
            throw BackupException("备份解压后的总大小超过上限，无法恢复。")
        }
        // A high expansion ratio on a large payload is a decompression bomb signature;
        // small archives are exempt because their ratio is naturally high.
        val compressed = counting.count.coerceAtLeast(1)
        if (uncompressedTotal > 8L * 1024 * 1024 &&
            uncompressedTotal / compressed > BackupFormat.Limits.MAX_EXPANSION_RATIO
        ) {
            throw BackupException("备份的压缩比例异常，已拒绝解压。")
        }
        return ExtractedArchive(
            manifest = parsedManifest,
            dataBytes = parsedData,
            manifestBytes = checkNotNull(manifestBytes),
            attachmentsDirectory = attachmentsDirectory,
            uncompressedBytes = uncompressedTotal,
        )
    }

    private fun requireDeclaredSize(entry: ZipEntry, max: Long, name: String) {
        val declared = entry.size
        if (declared > max) {
            throw BackupException("备份条目「$name」声明的解压大小超过上限，无法恢复。")
        }
    }

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(max, 64 * 1024))
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > max) {
                throw BackupException("备份中的单个条目超过大小上限，无法恢复。")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}

/** Fails instead of buffering an archive that is larger than the accepted limit. */
private class BoundedInputStream(
    input: InputStream,
    private val maxBytes: Long,
) : FilterInputStream(input) {

    private var consumed = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) {
            consumed += 1
            if (consumed > maxBytes) throw BackupException("备份文件超过大小上限，无法读取。")
        }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(buffer, offset, length)
        if (read > 0) {
            consumed += read
            if (consumed > maxBytes) throw BackupException("备份文件超过大小上限，无法读取。")
        }
        return read
    }
}

private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count: Long = 0
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count += 1 }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) count += it }
}
