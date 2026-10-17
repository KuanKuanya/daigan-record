package com.daigan.record.testing

import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.model.AccountDraft
import com.daigan.record.data.model.ArchivedAccount
import com.daigan.record.data.model.GameAccount
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.data.model.Owner
import com.daigan.record.data.model.OwnerDetail
import com.daigan.record.data.model.OwnerSummary
import com.daigan.record.data.repository.GameAccountRepository
import com.daigan.record.data.repository.OwnerRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

val fixedInstant: Instant = Instant.parse("2026-10-17T02:00:00Z")

class FixedTimeProvider(private val instant: Instant = fixedInstant) : TimeProvider {
    override fun now(): Instant = instant
}

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
    archivedWithOwner: Boolean = false,
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
    archivedWithOwner = archivedWithOwner,
)

class FakeOwnerRepository : OwnerRepository {
    val activeOwners = MutableStateFlow<List<OwnerSummary>>(emptyList())
    val archivedOwners = MutableStateFlow<List<OwnerSummary>>(emptyList())
    val ownersById = mutableMapOf<String, Owner>()
    val renamed = mutableListOf<Pair<String, String>>()
    val createdNicknames = mutableListOf<String>()

    override fun observeActiveOwners(): Flow<List<OwnerSummary>> = activeOwners

    override fun observeArchivedOwners(): Flow<List<OwnerSummary>> = archivedOwners

    override fun observeOwner(ownerId: String): Flow<OwnerDetail?> = combine(
        activeOwners,
        archivedOwners,
    ) { active, archived ->
        val summary = (active + archived).firstOrNull { it.owner.id == ownerId }
        summary?.let { found ->
            OwnerDetail(
                owner = found.owner,
                activeAccounts = found.accounts,
                archivedAccounts = emptyList(),
            )
        }
    }

    override suspend fun findOwner(ownerId: String): Owner? = ownersById[ownerId]

    override suspend fun createOwner(nickname: String): Owner {
        // Mirrors the real repository contract: the nickname is stored trimmed.
        val trimmed = nickname.trim()
        createdNicknames += trimmed
        val created = owner(id = "owner-${ownersById.size + 1}", number = ownersById.size + 1, nickname = trimmed)
        ownersById[created.id] = created
        activeOwners.value = activeOwners.value + OwnerSummary(created, emptyList())
        return created
    }

    override suspend fun renameOwner(ownerId: String, nickname: String) {
        renamed += ownerId to nickname
    }

    override suspend fun archiveOwner(ownerId: String) = Unit

    override suspend fun restoreOwner(ownerId: String) = Unit
}

class FakeGameAccountRepository : GameAccountRepository {
    val archivedAccounts = MutableStateFlow<List<ArchivedAccount>>(emptyList())
    val accountsById = mutableMapOf<String, GameAccount>()
    val createdDrafts = mutableListOf<AccountDraft>()
    val updatedDrafts = mutableListOf<AccountDraft>()

    override fun observeArchivedAccounts(): Flow<List<ArchivedAccount>> = archivedAccounts

    override suspend fun findAccount(accountId: String): GameAccount? = accountsById[accountId]

    override suspend fun createAccount(ownerId: String, draft: AccountDraft): GameAccount {
        createdDrafts += draft
        val entity = account(
            id = "account-${accountsById.size + 1}",
            ownerId = ownerId,
            gameName = draft.gameName,
            loginMethod = draft.loginMethod,
            password = draft.password.ifEmpty { null },
        )
        accountsById[entity.id] = entity
        return entity
    }

    override suspend fun updateAccount(accountId: String, draft: AccountDraft): GameAccount {
        updatedDrafts += draft
        val updated = checkNotNull(accountsById[accountId]).copy(
            gameName = draft.gameName,
            loginMethod = draft.loginMethod,
            password = draft.password.ifEmpty { null },
        )
        accountsById[accountId] = updated
        return updated
    }

    override suspend fun archiveAccount(accountId: String) = Unit

    override suspend fun restoreAccount(accountId: String) = Unit
}

/** Exposes the repository flows for assertions without extra test utilities. */
fun OwnerRepository.activeOwnerIds(): Flow<List<String>> =
    observeActiveOwners().map { owners -> owners.map { it.owner.id } }
