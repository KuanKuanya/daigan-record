package com.daigan.record.testing

import com.daigan.record.data.model.GameAccount
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.data.model.Owner
import com.daigan.record.data.model.OwnerDetail
import java.time.Instant

val fixedInstant: Instant = Instant.parse("2026-10-17T02:00:00Z")

fun owner(
    id: String = "owner-1",
    number: Int = 1,
    nickname: String = "小满",
    archived: Boolean = false,
): Owner = Owner(
    id = id,
    number = number,
    nickname = nickname,
    createdAt = fixedInstant,
    archived = archived,
)

fun account(
    id: String = "account-1",
    ownerId: String = "owner-1",
    gameName: String = "重返未来：1999",
    server: String = "官服",
    uid: String = "10000628",
    loginAccount: String = "demo-xiaoman",
    loginMethod: LoginMethod = LoginMethod.PASSWORD,
    password: String? = "demo1999",
    archived: Boolean = false,
): GameAccount = GameAccount(
    id = id,
    ownerId = ownerId,
    gameName = gameName,
    server = server,
    uid = uid,
    loginAccount = loginAccount,
    loginMethod = loginMethod,
    password = password,
    createdAt = fixedInstant,
    archived = archived,
)

fun ownerDetail(
    owner: Owner = owner(),
    activeAccounts: List<GameAccount> = listOf(account()),
    archivedAccounts: List<GameAccount> = emptyList(),
): OwnerDetail = OwnerDetail(
    owner = owner,
    activeAccounts = activeAccounts,
    archivedAccounts = archivedAccounts,
)
