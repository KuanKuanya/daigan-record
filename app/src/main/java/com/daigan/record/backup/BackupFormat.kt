package com.daigan.record.backup

import java.io.IOException

/**
 * The whole-dataset backup format. It is deliberately separate from the single-template
 * exchange format (`daigan-template`, see `TemplateFileCodec`): a template file is never
 * a backup and a backup never doubles as a template.
 *
 * Plain archive layout (a ZIP stream):
 *
 * ```
 * manifest.json                 format id/version, source schema, export time, table
 *                               counts, data checksum and the attachment manifest
 * data.json                     every durable business row (typed JSON)
 * attachments/<relativePath>    the raw screenshot bytes, exactly as stored
 * ```
 *
 * Encrypted backup layout (a small binary envelope):
 *
 * ```
 * "DAIGANBK"                    magic, 8 bytes ASCII
 * envelopeVersion (1 byte)      currently 1
 * kdfId (1 byte)                1 = PBKDF2WithHmacSHA256
 * iterations (4 bytes, BE)      210000
 * saltLength (1 byte)           16
 * salt                          16 random bytes
 * ivLength (1 byte)             12
 * iv                            12 random bytes (AES/GCM nonce)
 * ciphertext                    AES-256-GCM over the whole ZIP stream, 128-bit tag
 * ```
 *
 * The envelope header is plaintext on purpose: the restore flow must be able to tell
 * that a password is required (and which format version it is) before decrypting.
 */
object BackupFormat {

    const val FORMAT_ID = "daigan-backup"
    const val FORMAT_VERSION = 1
    const val FILE_EXTENSION = "dgbk"
    const val MIME_TYPE = "application/octet-stream"

    const val MANIFEST_ENTRY = "manifest.json"
    const val DATA_ENTRY = "data.json"
    const val ATTACHMENT_PREFIX = "attachments/"

    /** A short, non-secret generator label stored in the manifest. */
    const val GENERATOR = "daigan-record-android"

    /**
     * Hard resource limits. They are enforced both on declared ZIP metadata and on the
     * bytes actually read, so a lying header cannot exceed them.
     */
    object Limits {
        /** Largest accepted archive file (a single `.dgbk`). */
        const val MAX_ARCHIVE_BYTES: Long = 512L * 1024 * 1024

        /** Largest accepted manifest. */
        const val MAX_MANIFEST_BYTES: Int = 1 * 1024 * 1024

        /** Largest accepted structured data document. */
        const val MAX_DATA_BYTES: Int = 64 * 1024 * 1024

        /** Total entries (manifest + data + attachments) in one archive. */
        const val MAX_ENTRIES: Int = 5_000

        /** Attachments per archive. */
        const val MAX_ATTACHMENTS: Int = 2_000

        /** One attachment's uncompressed size. */
        const val MAX_ATTACHMENT_BYTES: Long = 32L * 1024 * 1024

        /** Total uncompressed attachment bytes. */
        const val MAX_ATTACHMENT_TOTAL_BYTES: Long = 384L * 1024 * 1024

        /** Maximum total expansion (uncompressed / archive bytes). */
        const val MAX_EXPANSION_RATIO: Long = 200

        /** Largest accepted password (characters). */
        const val MAX_PASSWORD_LENGTH: Int = 256
    }
}

/**
 * A backup problem that is safe and useful to show to a nontechnical user.
 *
 * Messages never include passwords, derived keys, account credentials or decrypted row
 * contents; they only describe the failed check.
 */
open class BackupException(message: String, cause: Throwable? = null) : IOException(message)

/** Thrown when the user supplied a wrong password or the ciphertext was modified. */
class BackupPasswordException(message: String) : BackupException(message)
