package com.daigan.record.data.model

import java.time.Instant

/** A game account that belongs to exactly one [Owner]. */
data class GameAccount(
    val id: String,
    val ownerId: String,
    val gameName: String,
    val server: String,
    val uid: String,
    val loginAccount: String,
    val loginMethod: LoginMethod,
    val password: String?,
    val createdAt: Instant,
    val archived: Boolean,
    /**
     * True only when the account is archived because its owner was archived in the
     * same batch. Restoring the owner restores exactly these accounts; independently
     * archived accounts stay archived.
     */
    val archivedWithOwner: Boolean,
    /**
     * Template preselected for a new order on this account. Null for legacy accounts and
     * for accounts that do not use a template; using the account and its orders never
     * requires one.
     */
    val defaultTemplateId: String? = null,
) {
    val hasStoredPassword: Boolean
        get() = loginMethod == LoginMethod.PASSWORD && !password.isNullOrEmpty()
}

/** Editable account fields collected by the account form. */
data class AccountDraft(
    val gameName: String = "",
    val server: String = "",
    val uid: String = "",
    val loginAccount: String = "",
    val loginMethod: LoginMethod = LoginMethod.VERIFICATION_CODE,
    val password: String = "",
    /** Optional creation default for new orders; never required to use the account. */
    val defaultTemplateId: String? = null,
) {
    /**
     * Trims every text field and drops the password whenever the account uses
     * verification-code login, so a stale password can never survive a mode switch.
     */
    fun normalized(): AccountDraft = copy(
        gameName = gameName.trim(),
        server = server.trim(),
        uid = uid.trim(),
        loginAccount = loginAccount.trim(),
        password = if (loginMethod == LoginMethod.PASSWORD) password else "",
    )

    /** Returns per-field Chinese error messages; empty means the draft is valid. */
    fun validate(): Map<AccountField, String> {
        val normalized = normalized()
        val errors = mutableMapOf<AccountField, String>()
        if (normalized.gameName.isEmpty()) errors[AccountField.GAME_NAME] = "请填写游戏名称"
        if (normalized.gameName.length > MAX_TEXT_LENGTH) errors[AccountField.GAME_NAME] = "游戏名称最多 $MAX_TEXT_LENGTH 个字符"
        if (normalized.server.isEmpty()) errors[AccountField.SERVER] = "请填写区服"
        if (normalized.server.length > MAX_TEXT_LENGTH) errors[AccountField.SERVER] = "区服最多 $MAX_TEXT_LENGTH 个字符"
        if (normalized.uid.isEmpty()) errors[AccountField.UID] = "请填写游戏 UID"
        if (normalized.uid.length > MAX_TEXT_LENGTH) errors[AccountField.UID] = "UID 最多 $MAX_TEXT_LENGTH 个字符"
        if (normalized.loginAccount.isEmpty()) errors[AccountField.LOGIN_ACCOUNT] = "请填写登录账号"
        if (normalized.loginAccount.length > MAX_TEXT_LENGTH) errors[AccountField.LOGIN_ACCOUNT] = "登录账号最多 $MAX_TEXT_LENGTH 个字符"
        if (normalized.loginMethod == LoginMethod.PASSWORD && normalized.password.isBlank()) {
            errors[AccountField.PASSWORD] = "密码登录需要填写密码；如使用验证码登录请切换登录方式"
        }
        return errors
    }

    companion object {
        const val MAX_TEXT_LENGTH = 60

        fun from(account: GameAccount): AccountDraft = AccountDraft(
            gameName = account.gameName,
            server = account.server,
            uid = account.uid,
            loginAccount = account.loginAccount,
            loginMethod = account.loginMethod,
            password = account.password.orEmpty(),
            defaultTemplateId = account.defaultTemplateId,
        )
    }
}

enum class AccountField {
    GAME_NAME,
    SERVER,
    UID,
    LOGIN_ACCOUNT,
    PASSWORD,
}
