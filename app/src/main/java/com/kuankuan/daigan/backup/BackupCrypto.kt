package com.kuankuan.daigan.backup

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optional password protection for a backup.
 *
 * Algorithms (platform primitives only, no home-made construction):
 *
 * - Key derivation: `PBKDF2WithHmacSHA256`, 210,000 iterations, 256-bit key,
 *   fresh 16-byte random salt per backup.
 * - Encryption: `AES/GCM/NoPadding`, 256-bit key, fresh 12-byte random nonce per
 *   backup, 128-bit authentication tag. GCM authenticates the whole archive, so a
 *   modified ciphertext fails instead of decrypting into plausible data.
 *
 * The envelope is versioned ([ENVELOPE_VERSION]) and records the KDF id and iteration
 * count, so a future change can be detected instead of guessed. Every header field is
 * validated within fixed bounds before any key derivation work is done.
 *
 * The password and derived key are never written to disk, preferences or logs.
 * Decrypted archive bytes exist only in private temporary staging during preparation. The caller's `CharArray` is cleared as soon as the key is
 * derived.
 */
object BackupCrypto {

    const val MAGIC = "DAIGANBK"
    const val ENVELOPE_VERSION = 1
    const val KDF_PBKDF2_HMAC_SHA256 = 1
    const val ITERATIONS = 210_000
    const val SALT_BYTES = 16
    const val IV_BYTES = 12
    const val KEY_BITS = 256
    const val TAG_BITS = 128

    /** Header size with the current fixed salt/nonce lengths (used for progress only). */
    const val HEADER_BYTES = 8 + 1 + 1 + 4 + 1 + SALT_BYTES + 1 + IV_BYTES

    data class EnvelopeHeader(
        val envelopeVersion: Int,
        val kdfId: Int,
        val iterations: Int,
        val salt: ByteArray,
        val iv: ByteArray,
    ) {
        // ByteArray has identity equality; the header is only used as parsed metadata.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /** True when [file] starts with the encrypted envelope magic. */
    fun isEncrypted(file: File): Boolean = readPrefix(file, MAGIC.length).contentEquals(MAGIC.toByteArray())

    /**
     * Reads and validates the envelope header. Returns null for a plain ZIP archive.
     * Rejects unknown envelope versions, unknown KDFs and out-of-range parameters before
     * any key derivation.
     */
    fun readHeader(file: File): EnvelopeHeader? {
        if (!isEncrypted(file)) {
            if (!isZip(file)) {
                throw BackupException("这不是本应用的备份文件，无法识别文件格式。")
            }
            return null
        }
        DataInputStream(file.inputStream().buffered()).use { input ->
            val magic = ByteArray(MAGIC.length)
            input.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != MAGIC) {
                throw BackupException("这不是本应用的备份文件，无法识别文件格式。")
            }
            val version = input.readUnsignedByte()
            if (version != ENVELOPE_VERSION) {
                throw BackupException("该加密备份的格式版本（$version）不受支持，请使用更新版本的应用读取。")
            }
            val kdfId = input.readUnsignedByte()
            if (kdfId != KDF_PBKDF2_HMAC_SHA256) {
                throw BackupException("该备份使用了不支持的密钥派生方式，无法恢复。")
            }
            val iterations = input.readInt()
            if (iterations !in 10_000..2_000_000) {
                throw BackupException("该备份的加密参数超出支持范围，无法恢复。")
            }
            val saltLength = input.readUnsignedByte()
            if (saltLength !in 8..64) {
                throw BackupException("该备份的加密参数无效，无法恢复。")
            }
            val salt = ByteArray(saltLength).also(input::readFully)
            val ivLength = input.readUnsignedByte()
            if (ivLength !in 12..16) {
                throw BackupException("该备份的加密参数无效，无法恢复。")
            }
            val iv = ByteArray(ivLength).also(input::readFully)
            return EnvelopeHeader(version, kdfId, iterations, salt, iv)
        }
    }

    /** Encrypts [plain] into [out]. [password] is cleared before returning. */
    fun encryptTo(plain: File, out: OutputStream, password: CharArray) {
        validatePassword(password)
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(password, salt, ITERATIONS)
        try {
            out.write(MAGIC.toByteArray(Charsets.US_ASCII))
            out.write(ENVELOPE_VERSION)
            out.write(KDF_PBKDF2_HMAC_SHA256)
            out.write(intToBytes(ITERATIONS))
            out.write(salt.size)
            out.write(salt)
            out.write(iv.size)
            out.write(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            plain.inputStream().buffered().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    val encrypted = cipher.update(buffer, 0, read)
                    if (encrypted != null && encrypted.isNotEmpty()) out.write(encrypted)
                }
            }
            val tail = cipher.doFinal()
            if (tail.isNotEmpty()) out.write(tail)
            out.flush()
        } finally {
            password.fill('\u0000')
        }
    }

    /**
     * Decrypts [encrypted] into [out]. The GCM tag is verified by [Cipher.doFinal] after
     * the whole stream was processed, so a wrong password or a modified archive fails
     * with [BackupPasswordException] instead of writing partial plaintext as "success".
     * [password] is cleared before returning.
     */
    fun decryptTo(encrypted: File, out: OutputStream, password: CharArray) {
        validatePassword(password)
        val header = try {
            readHeader(encrypted) ?: throw BackupException("该文件没有加密，不需要输入密码。")
        } catch (error: Throwable) {
            password.fill('\u0000')
            throw error
        }
        val key = deriveKey(password, header.salt, header.iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, header.iv))
            encrypted.inputStream().buffered().use { raw ->
                val input = DataInputStream(raw)
                // Skip the exact header that was just parsed. `skipNBytes` is a Java 12
                // API that is not available on every supported Android version, so the
                // skip is explicit and fails if the header is truncated.
                skipFully(
                    input,
                    (MAGIC.length + 1 + 1 + 4 + 1 + header.salt.size + 1 + header.iv.size)
                        .toLong(),
                )
                val buffer = ByteArray(BUFFER_BYTES)
                try {
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        val decrypted = cipher.update(buffer, 0, read)
                        if (decrypted != null && decrypted.isNotEmpty()) out.write(decrypted)
                    }
                    val tail = cipher.doFinal()
                    if (tail.isNotEmpty()) out.write(tail)
                    out.flush()
                } catch (error: AEADBadTagException) {
                    throw BackupPasswordException("密码不正确，或备份文件已被修改。")
                } catch (error: javax.crypto.BadPaddingException) {
                    throw BackupPasswordException("密码不正确，或备份文件已被修改。")
                } catch (error: javax.crypto.IllegalBlockSizeException) {
                    throw BackupException("备份文件不完整或已损坏，无法恢复。", error)
                }
            }
        } finally {
            password.fill('\u0000')
        }
    }

    private fun validatePassword(password: CharArray) {
        if (password.isEmpty() || password.size > BackupFormat.Limits.MAX_PASSWORD_LENGTH) {
            password.fill('\u0000')
            throw IllegalArgumentException("密码长度必须为 1 至 ${BackupFormat.Limits.MAX_PASSWORD_LENGTH} 个字符")
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val encoded = factory.generateSecret(spec).encoded
            return try { SecretKeySpec(encoded, "AES") } finally { encoded.fill(0) }
        } finally {
            password.fill('\u0000')
            spec.clearPassword()
        }
    }

    /** A plain backup must start with the ZIP local-file-header signature. */
    fun isZip(file: File): Boolean {
        val prefix = readPrefix(file, 2)
        return prefix.size == 2 && prefix[0] == 'P'.code.toByte() && prefix[1] == 'K'.code.toByte()
    }

    private fun readPrefix(file: File, length: Int): ByteArray = file.inputStream().use { input ->
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(buffer, read, length - read)
            if (count < 0) break
            read += count
        }
        if (read == 0) ByteArray(0) else buffer.copyOf(read)
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val scratch = ByteArray(64)
        while (remaining > 0) {
            val read = input.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
            if (read < 0) throw BackupException("备份文件不完整或已损坏，无法恢复。")
            remaining -= read
        }
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private const val BUFFER_BYTES = 64 * 1024
}
