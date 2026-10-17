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
 * Game-account persistence boundary.
 *
 * Credentials stay in the repository/DAO layer: no logging, no analytics and no
 * file writes happen here. ViewModels only see domain models.
 */
interface GameAccountRepository {

    /** Archived accounts whose owner is still active, for the archived-record view. */
    fun observeArchivedAccounts(): Flow<List<ArchivedAccount>>

    suspend fun findAccount(accountId: String): GameAccount?

    suspend fun createAccount(ownerId: String, draft: AccountDraft): GameAccount

    suspend fun updateAccount(accountId: String, draft: AccountDraft): GameAccount

    suspend fun archiveAccount(accountId: String)

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
            val owner = ownerDao.findById(ownerId) ?: throw IllegalArgumentException("老板不存在")
            check(owner.archivedAt == null) { "老板已归档，无法新增游戏账号" }
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
            accountDao.findById(accountId) ?: throw IllegalArgumentException("游戏账号不存在")
            accountDao.setArchivedAt(accountId = accountId, archivedAt = now)
        }
    }

    override suspend fun restoreAccount(accountId: String) {
        val now = timeProvider.now()
        database.withTransaction {
            accountDao.findById(accountId) ?: throw IllegalArgumentException("游戏账号不存在")
            accountDao.clearArchivedAt(accountId = accountId, updatedAt = now)
        }
    }
}
