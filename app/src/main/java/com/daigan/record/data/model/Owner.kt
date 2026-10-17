package com.daigan.record.data.model

import java.time.Instant

/**
 * A customer ("老板") whose game accounts are managed offline.
 *
 * [number] is assigned once by the database and never changes. It is the stable,
 * human readable identifier shown in the UI; [nickname] may be edited freely.
 */
data class Owner(
    val id: String,
    val number: Int,
    val nickname: String,
    val createdAt: Instant,
    val archived: Boolean,
) {
    val displayNumber: String
        get() = OwnerNumber.format(number)
}

/** Formats the internal owner sequence into the "B001" style number shown to the user. */
object OwnerNumber {
    private const val PREFIX = "B"
    private const val MIN_DIGITS = 3

    fun format(value: Int): String = PREFIX + value.toString().padStart(MIN_DIGITS, '0')
}

/** Validation rules shared by the form and the repository. */
object OwnerValidation {
    const val MAX_NICKNAME_LENGTH = 30

    /** Returns a user facing Chinese error message, or null when the nickname is valid. */
    fun nicknameError(raw: String): String? {
        val nickname = raw.trim()
        return when {
            nickname.isEmpty() -> "请填写老板昵称"
            nickname.length > MAX_NICKNAME_LENGTH -> "昵称最多 $MAX_NICKNAME_LENGTH 个字符"
            else -> null
        }
    }
}

/** One card in the account list, grouped by owner. */
data class OwnerSummary(
    val owner: Owner,
    val accounts: List<GameAccount>,
) {
    val activeAccountCount: Int get() = accounts.size

    val gameNames: List<String>
        get() = accounts.map { it.gameName }.distinct()
}

/** Owner header plus every game account, including archived ones. */
data class OwnerDetail(
    val owner: Owner,
    val activeAccounts: List<GameAccount>,
    val archivedAccounts: List<GameAccount>,
)

/** An archived account shown in the archived-record view with its owner context. */
data class ArchivedAccount(
    val ownerId: String,
    val ownerNumber: String,
    val ownerNickname: String,
    val account: GameAccount,
)
