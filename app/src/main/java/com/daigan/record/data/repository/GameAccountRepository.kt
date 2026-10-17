package com.daigan.record.data.repository

import androidx.room.withTransaction
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.local.dao.GameAccountDao
import com.daigan.record.data.local.dao.OwnerDao
import com.daigan.record.data.local.entity.GameAccountEntity
import com.daigan.record.data.model.AccountDraft
import com.daigan.record.data.model.ArchivedAccount
import com.daigan.record.data.model.GameAccount
import com.daigan.record.data.model.LoginMethod
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * User facing reason for every account write that is refused while the parent owner
 * is archived. UI and repository share the same wording so the message a stale form
 * shows matches the message the write layer enforces.
 */
const val ARCHIVED_OWNER_ACCOUNT_MESSAGE = "老板已归档，请先恢复老板后再操作游戏账号"

/**
 * Game-account persistence boundary.
 *
 * Every write is validated inside its own database transaction: while an owner is
 * archived its accounts are read-only, so hiding buttons is not the only defence.
 * Credentials stay in the repository/DAO layer: no logging, no analytics and no file
 * writes happen here. ViewModels only see domain models.
 */
interface GameAccountRepository {

    /** Archived accounts whose owner is still active, for the archived-record view. */
    fun observeArchivedAccounts(): Flow<List<ArchivedAccount>>

    suspend fun findAccount(accountId: String): GameAccount?

    /** Rejected when the owner is archived. */
    suspend fun createAccount(ownerId: String, draft: AccountDraft): GameAccount

    /** Rejected when the owner is archived or the account itself is archived. */
    suspend fun updateAccount(accountId: String, draft: AccountDraft): GameAccount

    /** Rejected when the owner is archived. Already archived accounts are a no-op. */
    suspend fun archiveAccount(accountId: String)

    /** Rejected when the owner is archived. Already active accounts are a no-op. */
    suspend fun restoreAccount(accountId: String)
}

@Singleton
class DefaultGameAccountRepository @Inject constructor(
    private val database: RecordDatabase,
    private val ownerDao: OwnerDao,
    private val accountDao: GameAccountDao,
    private val timeProvider: TimeProvider,
) : GameAccountRepository {

    override fun observeArchivedAccounts(): Flow<List<ArchivedAccount>> =
        combine(ownerDao.observeAll(), accountDao.observeArchived()) { owners, accounts ->
            val ownersById = owners.associateBy { it.id }
            accounts.mapNotNull { account ->
                val owner = ownersById[account.ownerId] ?: return@mapNotNull null
                // Accounts archived together with their owner are listed under that owner.
                if (owner.archivedAt != null) return@mapNotNull null
                ArchivedAccount(
                    ownerId = owner.id,
                    ownerNumber = owner.toModel().displayNumber,
                    ownerNickname = owner.nickname,
                    account = account.toModel(),
                )
            }
        }

    override suspend fun findAccount(accountId: String): GameAccount? =
        accountDao.findById(accountId)?.toModel()

    override suspend fun createAccount(ownerId: String, draft: AccountDraft): GameAccount {
        val normalized = draft.normalized()
        normalized.validate().values.firstOrNull()?.let { throw IllegalArgumentException(it) }

        val now = timeProvider.now()
        return database.withTransaction {
            requireActiveOwner(ownerId)
            val entity = GameAccountEntity(
                id = UUID.randomUUID().toString(),
                ownerId = ownerId,
                gameName = normalized.gameName,
                server = normalized.server,
                uid = normalized.uid,
                loginAccount = normalized.loginAccount,
                loginMethod = normalized.loginMethod,
                // A verification-code account never keeps a password value.
                password = normalized.password.takeIf { normalized.loginMethod == LoginMethod.PASSWORD && it.isNotBlank() },
                createdAt = now,
                updatedAt = now,
                archivedAt = null,
            )
            accountDao.insert(entity)
            entity.toModel()
        }
    }

    override suspend fun updateAccount(accountId: String, draft: AccountDraft): GameAccount {
        val normalized = draft.normalized()
        normalized.validate().values.firstOrNull()?.let { throw IllegalArgumentException(it) }

        val now = timeProvider.now()
        return database.withTransaction {
            val existing = accountDao.findById(accountId) ?: throw IllegalArgumentException("游戏账号不存在")
            // A stale form may be submitted after the owner was archived elsewhere.
            requireActiveOwner(existing.ownerId)
            require(existing.archivedAt == null) { "游戏账号已归档，请先恢复后再编辑" }
            accountDao.update(
                accountId = accountId,
                gameName = normalized.gameName,
                server = normalized.server,
                uid = normalized.uid,
                loginAccount = normalized.loginAccount,
                loginMethod = normalized.loginMethod,
                // Switching to verification-code login clears the stored password.
                password = normalized.password.takeIf { normalized.loginMethod == LoginMethod.PASSWORD && it.isNotBlank() },
                updatedAt = now,
            )
            existing.copy(
                gameName = normalized.gameName,
                server = normalized.server,
                uid = normalized.uid,
                loginAccount = normalized.loginAccount,
                loginMethod = normalized.loginMethod,
                password = normalized.password.takeIf { normalized.loginMethod == LoginMethod.PASSWORD && it.isNotBlank() },
                updatedAt = now,
            ).toModel()
        }
    }

    override suspend fun archiveAccount(accountId: String) {
        val now = timeProvider.now()
        database.withTransaction {
            val account = accountDao.findById(accountId) ?: throw IllegalArgumentException("游戏账号不存在")
            requireActiveOwner(account.ownerId)
            // Idempotent: archiving twice keeps the original archive instant.
            if (account.archivedAt != null) return@withTransaction
            accountDao.archiveIndependently(accountId = accountId, archivedAt = now)
        }
    }

    override suspend fun restoreAccount(accountId: String) {
        val now = timeProvider.now()
        database.withTransaction {
            val account = accountDao.findById(accountId) ?: throw IllegalArgumentException("游戏账号不存在")
            // While the owner is archived its accounts are read-only, so an archived
            // child can never be pulled back into an archived parent.
            requireActiveOwner(account.ownerId)
            if (account.archivedAt == null) return@withTransaction
            accountDao.restoreIndependently(accountId = accountId, updatedAt = now)
        }
    }

    /** Must be called inside the write transaction so the state cannot change underneath. */
    private suspend fun requireActiveOwner(ownerId: String) {
        val owner = ownerDao.findById(ownerId) ?: throw IllegalArgumentException("老板不存在")
        require(owner.archivedAt == null) { ARCHIVED_OWNER_ACCOUNT_MESSAGE }
    }
}
