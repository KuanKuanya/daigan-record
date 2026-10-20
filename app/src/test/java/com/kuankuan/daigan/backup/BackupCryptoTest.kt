package com.kuankuan.daigan.backup

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BackupCryptoTest {
    @Test fun encryptedRoundTripClearsPasswordsAndRejectsTampering() {
        val plain = File.createTempFile("backup", ".zip")
        val encrypted = File.createTempFile("backup", ".dgbk")
        try {
            val bytes = ByteArray(131073) { (it % 251).toByte() }
            plain.writeBytes(bytes)
            val password = "测试 secret".toCharArray()
            val output = ByteArrayOutputStream()
            BackupCrypto.encryptTo(plain, output, password)
            assertTrue(password.all { it == '\u0000' })
            encrypted.writeBytes(output.toByteArray())
            val restored = ByteArrayOutputStream()
            BackupCrypto.decryptTo(encrypted, restored, "测试 secret".toCharArray())
            assertArrayEquals(bytes, restored.toByteArray())
            val wrongPassword = "wrong-password".toCharArray()
            try {
                BackupCrypto.decryptTo(encrypted, ByteArrayOutputStream(), wrongPassword)
                fail("Wrong password must fail authentication")
            } catch (_: BackupPasswordException) { }
            assertTrue(wrongPassword.all { it == '\u0000' })
            val changed = encrypted.readBytes()
            changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
            encrypted.writeBytes(changed)
            try {
                BackupCrypto.decryptTo(encrypted, ByteArrayOutputStream(), "测试 secret".toCharArray())
                fail("Modified ciphertext must fail authentication")
            } catch (_: BackupPasswordException) { }
        } finally { plain.delete(); encrypted.delete() }
    }

    @Test fun oversizedDecryptPasswordIsRejectedAndCleared() {
        val plain = File.createTempFile("backup", ".zip")
        val encrypted = File.createTempFile("backup", ".dgbk")
        val password = CharArray(BackupFormat.Limits.MAX_PASSWORD_LENGTH + 1) { 'x' }
        try {
            plain.writeText("payload")
            val output = ByteArrayOutputStream()
            BackupCrypto.encryptTo(plain, output, "secret".toCharArray())
            encrypted.writeBytes(output.toByteArray())
            try {
                BackupCrypto.decryptTo(encrypted, ByteArrayOutputStream(), password)
                fail("Over-limit passwords must be rejected before key derivation")
            } catch (_: IllegalArgumentException) { }
            assertTrue(password.all { it == '\u0000' })
        } finally { plain.delete(); encrypted.delete() }
    }
}
