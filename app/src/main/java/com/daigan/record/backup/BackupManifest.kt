package com.daigan.record.backup

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The attachment part of the manifest: identity, declaration and integrity check. */
data class BackupAttachmentEntry(
    val id: String,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
)

/**
 * `manifest.json` of a backup archive.
 *
 * It is read first so that a restore can validate compatibility and declared sizes
 * before extracting anything. It contains no credentials and no business row values,
 * only counts and attachment metadata.
 */
data class BackupManifest(
    val formatVersion: Int,
    val schemaVersion: Int,
    val exportedAt: Long,
    val generator: String,
    val dataSha256: String,
    val tableCounts: Map<String, Int>,
    val attachments: List<BackupAttachmentEntry>,
    val attachmentTotalBytes: Long,
) {
    val recordCount: Int get() = tableCounts.values.sum()
}

object BackupManifestCodec {

    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
        allowTrailingComma = false
    }

    private val hexPattern = Regex("^[0-9a-f]{64}$")

    fun encode(manifest: BackupManifest): String {
        val root = JsonObject(
            linkedMapOf(
                "format" to JsonPrimitive(BackupFormat.FORMAT_ID),
                "formatVersion" to JsonPrimitive(manifest.formatVersion),
                "schemaVersion" to JsonPrimitive(manifest.schemaVersion),
                "exportedAt" to JsonPrimitive(manifest.exportedAt),
                "generator" to JsonPrimitive(manifest.generator),
                "dataSha256" to JsonPrimitive(manifest.dataSha256),
                "tableCounts" to JsonObject(
                    manifest.tableCounts.entries.associate { (name, count) ->
                        name to JsonPrimitive(count)
                    },
                ),
                "attachmentTotalBytes" to JsonPrimitive(manifest.attachmentTotalBytes),
                "attachments" to JsonArray(
                    manifest.attachments.map { entry ->
                        JsonObject(
                            linkedMapOf(
                                "id" to JsonPrimitive(entry.id),
                                "relativePath" to JsonPrimitive(entry.relativePath),
                                "mimeType" to JsonPrimitive(entry.mimeType),
                                "sizeBytes" to JsonPrimitive(entry.sizeBytes),
                                "sha256" to JsonPrimitive(entry.sha256),
                            ),
                        )
                    },
                ),
            ),
        )
        return json.encodeToString(JsonElement.serializer(), root)
    }

    /**
     * Parses and validates the manifest. [currentSchemaVersion] is the schema version
     * this build writes; a backup with a different version is rejected instead of being
     * guessed at.
     */
    fun decode(
        text: String,
        currentSchemaVersion: Int,
        supportedFormatVersion: Int = BackupFormat.FORMAT_VERSION,
    ): BackupManifest {
        JsonSafety.requireBoundedDepth(text, 16)
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw BackupException("备份清单已损坏，无法恢复。")
        if (root.string("format") != BackupFormat.FORMAT_ID) {
            throw BackupException("这不是本应用的完整备份文件。模板文件不能用于恢复全部资料。")
        }
        val formatVersion = root.int("formatVersion")
            ?: throw BackupException("备份清单缺少格式版本，无法恢复。")
        if (formatVersion != supportedFormatVersion) {
            throw BackupException(
                "该备份的格式版本（$formatVersion）不受支持，请使用对应版本的应用恢复。",
            )
        }
        val schemaVersion = root.int("schemaVersion")
            ?: throw BackupException("备份清单缺少数据库版本，无法恢复。")
        if (schemaVersion != currentSchemaVersion) {
            throw BackupException(
                "该备份来自数据版本 $schemaVersion，当前应用只支持版本 $currentSchemaVersion，无法恢复。",
            )
        }
        val exportedAt = root.long("exportedAt")
            ?: throw BackupException("备份清单缺少导出时间，无法恢复。")
        if (exportedAt <= 0) throw BackupException("备份清单的导出时间无效，无法恢复。")
        val dataSha256 = root.string("dataSha256").orEmpty()
        if (!hexPattern.matches(dataSha256)) {
            throw BackupException("备份清单缺少数据校验值，无法恢复。")
        }
        val generator = root.string("generator").orEmpty()

        val countsObject = root["tableCounts"] as? JsonObject
            ?: throw BackupException("备份清单缺少数据统计，无法恢复。")
        val expectedTables = BackupTableCatalog.includedTables.map { it.name }.toSet()
        if (countsObject.keys != expectedTables) {
            throw BackupException("备份的数据表清单与当前版本不一致，无法恢复。")
        }
        val tableCounts = LinkedHashMap<String, Int>()
        countsObject.forEach { (name, element) ->
            val count = (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
                ?: throw BackupException("备份的数据统计无效，无法恢复。")
            if (count < 0) throw BackupException("备份的数据统计无效，无法恢复。")
            tableCounts[name] = count
        }

        val attachmentArray = root["attachments"] as? JsonArray
            ?: throw BackupException("备份清单缺少截图清单，无法恢复。")
        if (attachmentArray.size > BackupFormat.Limits.MAX_ATTACHMENTS) {
            throw BackupException(
                "备份中的截图数量超过上限（${BackupFormat.Limits.MAX_ATTACHMENTS}），无法恢复。",
            )
        }
        val seenPaths = HashSet<String>()
        val seenIds = HashSet<String>()
        var totalBytes = 0L
        val entries = attachmentArray.map { element ->
            val objectValue = element as? JsonObject
                ?: throw BackupException("备份的截图清单格式无效，无法恢复。")
            val id = objectValue.string("id").orEmpty()
            val relativePath = objectValue.string("relativePath").orEmpty()
            val mimeType = objectValue.string("mimeType").orEmpty()
            val sizeBytes = objectValue.long("sizeBytes")
                ?: throw BackupException("备份的截图清单缺少大小，无法恢复。")
            val sha256 = objectValue.string("sha256").orEmpty()
            if (id.isBlank() || id.length > 128) {
                throw BackupException("备份的截图编号无效，无法恢复。")
            }
            if (!SafeNames.isSafeAttachmentName(relativePath)) {
                throw BackupException("备份包含不安全的截图文件名，无法恢复。")
            }
            if (!seenPaths.add(relativePath)) {
                throw BackupException("备份中重复出现截图文件「$relativePath」，无法恢复。")
            }
            if (!seenIds.add(id)) {
                throw BackupException("备份中重复出现截图编号，无法恢复。")
            }
            if (sizeBytes <= 0 || sizeBytes > BackupFormat.Limits.MAX_ATTACHMENT_BYTES) {
                throw BackupException("备份中的截图大小超出允许范围，无法恢复。")
            }
            if (!hexPattern.matches(sha256)) {
                throw BackupException("备份中的截图缺少有效校验值，无法恢复。")
            }
            if (mimeType.isBlank() || !mimeType.startsWith("image/") || mimeType.length > 100) {
                throw BackupException("备份中的截图类型无效，无法恢复。")
            }
            totalBytes += sizeBytes
            if (totalBytes > BackupFormat.Limits.MAX_ATTACHMENT_TOTAL_BYTES) {
                throw BackupException("备份中的截图总大小超过上限，无法恢复。")
            }
            BackupAttachmentEntry(id, relativePath, mimeType, sizeBytes, sha256)
        }
        val declaredTotal = root.long("attachmentTotalBytes") ?: totalBytes
        if (declaredTotal != totalBytes) {
            throw BackupException("备份的截图清单总大小不一致，无法恢复。")
        }
        return BackupManifest(
            formatVersion = formatVersion,
            schemaVersion = schemaVersion,
            exportedAt = exportedAt,
            generator = generator,
            dataSha256 = dataSha256,
            tableCounts = tableCounts,
            attachments = entries,
            attachmentTotalBytes = totalBytes,
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
}

/** File-name safety rules shared by the archive reader and the manifest. */
object SafeNames {

    private val attachmentName = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$")

    /**
     * A stored screenshot name: one plain file name with an extension, no separators,
     * no leading dot, no `..` segment, no absolute path and no control characters.
     */
    fun isSafeAttachmentName(name: String): Boolean {
        if (name.isEmpty() || name.length > 120) return false
        if (name.contains('/') || name.contains('\\') || name.contains('\u0000')) return false
        if (name.startsWith(".")) return false
        if (name.contains("..")) return false
        if (!attachmentName.matches(name)) return false
        // Must look like "<stem>.<ext>"; a bare stem would not round-trip through the
        // attachment directory contract.
        val dot = name.lastIndexOf('.')
        return dot in 1 until name.length - 1
    }

    /** True when a ZIP entry name is a plain relative path under the archive root. */
    fun isSafeEntryName(name: String): Boolean {
        if (name.isEmpty() || name.length > 200) return false
        if (name.startsWith("/") || name.contains('\\') || name.contains('\u0000')) return false
        if (name.endsWith("/")) return false
        val segments = name.split('/')
        return segments.none { it.isEmpty() || it == "." || it == ".." }
    }
}

/** SHA-256 helpers for the data document and for attachment bytes. */
object Sha256 {

    fun of(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
