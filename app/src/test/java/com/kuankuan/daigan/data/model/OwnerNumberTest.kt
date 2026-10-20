package com.kuankuan.daigan.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OwnerNumberTest {

    @Test
    fun `numbers are zero padded to three digits`() {
        assertEquals("B001", OwnerNumber.format(1))
        assertEquals("B042", OwnerNumber.format(42))
        assertEquals("B999", OwnerNumber.format(999))
    }

    @Test
    fun `numbers above three digits keep all digits`() {
        assertEquals("B1000", OwnerNumber.format(1000))
    }

    @Test
    fun `nickname is required`() {
        assertNotNull(OwnerValidation.nicknameError("   "))
        assertEquals("请填写老板昵称", OwnerValidation.nicknameError(""))
    }

    @Test
    fun `thirty Chinese characters are allowed`() {
        val nickname = "阿".repeat(OwnerValidation.MAX_NICKNAME_LENGTH)

        assertNull(OwnerValidation.nicknameError(nickname))
    }

    @Test
    fun `nicknames longer than thirty characters are rejected`() {
        val nickname = "阿".repeat(OwnerValidation.MAX_NICKNAME_LENGTH + 1)

        assertEquals(
            "昵称最多 ${OwnerValidation.MAX_NICKNAME_LENGTH} 个字符",
            OwnerValidation.nicknameError(nickname),
        )
    }
}
