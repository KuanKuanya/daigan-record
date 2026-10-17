package com.daigan.record.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDraftTest {

    @Test
    fun `normalized trims text fields`() {
        val draft = AccountDraft(
            gameName = "  鸣潮  ",
            server = " 国服 ",
            uid = " 10002136 ",
            loginAccount = " demo-acheng ",
        )

        val normalized = draft.normalized()

        assertEquals("鸣潮", normalized.gameName)
        assertEquals("国服", normalized.server)
        assertEquals("10002136", normalized.uid)
        assertEquals("demo-acheng", normalized.loginAccount)
    }

    @Test
    fun `switching to verification code login drops the stored password`() {
        val passwordLogin = AccountDraft(
            gameName = "鸣潮",
            server = "国服",
            uid = "10002136",
            loginAccount = "demo",
            loginMethod = LoginMethod.PASSWORD,
            password = "secret",
        )

        val switched = passwordLogin.copy(loginMethod = LoginMethod.VERIFICATION_CODE).normalized()

        assertEquals("", switched.password)
    }

    @Test
    fun `verification code login never requires a password`() {
        val draft = AccountDraft(
            gameName = "鸣潮",
            server = "国服",
            uid = "10002136",
            loginAccount = "demo",
            loginMethod = LoginMethod.VERIFICATION_CODE,
            password = "",
        )

        val errors = draft.validate()

        assertNull(errors[AccountField.PASSWORD])
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `password login requires a password`() {
        val draft = AccountDraft(
            gameName = "鸣潮",
            server = "国服",
            uid = "10002136",
            loginAccount = "demo",
            loginMethod = LoginMethod.PASSWORD,
            password = "   ",
        )

        val errors = draft.validate()

        assertTrue(errors.containsKey(AccountField.PASSWORD))
    }

    @Test
    fun `blank required fields produce Chinese errors`() {
        val errors = AccountDraft().validate()

        assertEquals("请填写游戏名称", errors[AccountField.GAME_NAME])
        assertEquals("请填写区服", errors[AccountField.SERVER])
        assertEquals("请填写游戏 UID", errors[AccountField.UID])
        assertEquals("请填写登录账号", errors[AccountField.LOGIN_ACCOUNT])
    }

    @Test
    fun `long Chinese values are accepted up to the documented limit`() {
        val longChinese = "重返未来".repeat(15) // 60 characters
        val draft = AccountDraft(
            gameName = longChinese,
            server = "官服",
            uid = "10000628",
            loginAccount = "demo",
            loginMethod = LoginMethod.VERIFICATION_CODE,
        )

        val errors = draft.validate()

        assertTrue("60 character game name should be valid", errors.isEmpty())
        assertEquals(60, draft.normalized().gameName.length)
    }

    @Test
    fun `values longer than the limit are rejected`() {
        val tooLong = "重".repeat(AccountDraft.MAX_TEXT_LENGTH + 1)
        val errors = AccountDraft(
            gameName = tooLong,
            server = "官服",
            uid = "10000628",
            loginAccount = "demo",
            loginMethod = LoginMethod.VERIFICATION_CODE,
        ).validate()

        assertTrue(errors.containsKey(AccountField.GAME_NAME))
    }

    @Test
    fun `from maps a stored account back into an editable draft`() {
        val stored = GameAccount(
            id = "account-1",
            ownerId = "owner-1",
            gameName = "鸣潮",
            server = "国服",
            uid = "10002136",
            loginAccount = "demo",
            loginMethod = LoginMethod.PASSWORD,
            password = "secret",
            createdAt = java.time.Instant.EPOCH,
            archived = false,
        )

        val draft = AccountDraft.from(stored)

        assertEquals("鸣潮", draft.gameName)
        assertEquals(LoginMethod.PASSWORD, draft.loginMethod)
        assertEquals("secret", draft.password)
        assertFalse(draft.validate().isNotEmpty())
    }
}
