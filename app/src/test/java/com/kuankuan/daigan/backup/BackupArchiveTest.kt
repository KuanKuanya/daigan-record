package com.kuankuan.daigan.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class BackupArchiveTest {
    private val empty = BackupDataSet(BackupTableCatalog.includedTables.map {
        BackupDataSet.TableRows(it, emptyList())
    })
    private fun manifest(data: ByteArray) = BackupManifest(
        formatVersion = BackupFormat.FORMAT_VERSION, schemaVersion = 7, exportedAt = 1000,
        generator = "test", dataSha256 = Sha256.of(data),
        tableCounts = empty.tables.associate { it.table.name to 0 },
        attachments = emptyList(), attachmentTotalBytes = 0,
    )

    @Test fun fullCatalogPlainArchiveRoundTrip() {
        val directory = Files.createTempDirectory("backup-test").toFile()
        try {
            val data = BackupDataCodec.encode(empty).toByteArray()
            val archive = ByteArrayOutputStream()
            BackupArchiveWriter.write(archive, BackupManifestCodec.encode(manifest(data)).toByteArray(),
                data, emptyList(), 1000)
            val result = BackupArchiveReader.read(ByteArrayInputStream(archive.toByteArray()), directory, 7)
            assertEquals(BackupTableCatalog.includedTables.size,
                BackupDataCodec.decode(result.dataBytes.toString(Charsets.UTF_8)).tables.size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun traversalAndUnlistedEntriesAreRejectedWithoutEscapingStaging() {
        listOf("../outside", "attachments/../../outside", "unknown.json").forEach { name ->
            val directory = Files.createTempDirectory("backup-test").toFile()
            try {
                val data = BackupDataCodec.encode(empty).toByteArray()
                val bytes = ByteArrayOutputStream()
                ZipOutputStream(bytes).use { zip ->
                    listOf("manifest.json" to BackupManifestCodec.encode(manifest(data)).toByteArray(),
                        "data.json" to data, name to byteArrayOf(1)).forEach { (path, content) ->
                        zip.putNextEntry(ZipEntry(path)); zip.write(content); zip.closeEntry()
                    }
                }
                try {
                    BackupArchiveReader.read(ByteArrayInputStream(bytes.toByteArray()), directory, 7)
                    fail("Unsafe entry accepted: $name")
                } catch (_: BackupException) { }
                assertFalse(File(directory, "outside").exists())
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun missingDeclaredScreenshotIsRejected() {
        val directory = Files.createTempDirectory("backup-test").toFile()
        try {
            val data = BackupDataCodec.encode(empty).toByteArray()
            val declared = manifest(data).copy(attachments = listOf(
                BackupAttachmentEntry("id", "missing.jpg", "image/jpeg", 1, Sha256.of(byteArrayOf(1)))
            ), attachmentTotalBytes = 1)
            val archive = ByteArrayOutputStream()
            BackupArchiveWriter.write(archive, BackupManifestCodec.encode(declared).toByteArray(),
                data, emptyList(), 1000)
            try {
                BackupArchiveReader.read(ByteArrayInputStream(archive.toByteArray()), directory, 7)
                fail("Missing screenshot accepted")
            } catch (_: BackupException) { }
        } finally { directory.deleteRecursively() }
    }

    @Test fun truncatedCentralDirectoryIsRejectedBeforeExtraction() {
        val directory = Files.createTempDirectory("backup-test").toFile()
        try {
            val data = BackupDataCodec.encode(empty).toByteArray()
            val archive = ByteArrayOutputStream()
            BackupArchiveWriter.write(archive, BackupManifestCodec.encode(manifest(data)).toByteArray(),
                data, emptyList(), 1000)
            val file = File(directory, "truncated.zip")
            val bytes = archive.toByteArray()
            file.writeBytes(bytes.copyOf(bytes.size - 22))
            try {
                BackupArchiveReader.read(file, File(directory, "extract"), 7)
                fail("Truncated central directory accepted")
            } catch (_: BackupException) { }
        } finally { directory.deleteRecursively() }
    }

    @Test fun modifiedDataChecksumIsRejected() {
        val directory = Files.createTempDirectory("backup-test").toFile()
        try {
            val data = BackupDataCodec.encode(empty).toByteArray()
            val archive = ByteArrayOutputStream()
            BackupArchiveWriter.write(archive, BackupManifestCodec.encode(manifest(data)).toByteArray(),
                data + byteArrayOf(32), emptyList(), 1000)
            try {
                BackupArchiveReader.read(ByteArrayInputStream(archive.toByteArray()), directory, 7)
                fail("Checksum mismatch accepted")
            } catch (_: BackupException) { }
        } finally { directory.deleteRecursively() }
    }
}
