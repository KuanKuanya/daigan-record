package com.daigan.record.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.local.dao.GameAccountDao
import com.daigan.record.data.local.dao.OwnerDao
import com.daigan.record.data.local.entity.OwnerEntity
import com.daigan.record.data.model.Owner
import com.daigan.record.data.model.OwnerDetail
import com.daigan.record.data.model.OwnerSummary
import com.daigan.record.data.model.OwnerValidation
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Owner ("老板") persistence boundary.
 *
 * ViewModels depend on this interface only; they never touch DAOs or SQLite.
 */
interface OwnerRepository {

    fun observeActiveOwners(): Flow<List<OwnerSummary>>

    fun observeArchivedOwners(): Flow<List<OwnerSummary>>

    fun observeOwner(ownerId: String): Flow<OwnerDetail?>

    suspend fun findOwner(ownerId: String): Owner?

    /** Creates an owner with a number that is allocated inside the insert transaction. */
    suspend fun createOwner(nickname: String): Owner

    /** Changes the nickname only; the owner number is immutable. */
    suspend fun renameOwner(ownerId: String, nickname: String)

    /** Archives the owner and its currently active accounts in one transaction. */
    suspend fun archiveOwner(ownerId: String)

    /** Restores the owner and exactly the accounts archived together with it. */
    suspend fun restoreOwner(ownerId: String)
}

@Singleton
class DefaultOwnerRepository @Inject constructor(
    private val database: RecordDatabase,
    private val ownerDao: OwnerDao,
    private val accountDao: GameAccountDao,
    private val timeProvider: TimeProvider,
) : OwnerRepository {

    override fun observeActiveOwners(): Flow<List<OwnerSummary>> =
        combine(ownerDao.observeActive(), accountDao.observeActive()) { owners, accounts ->
            val accountsByOwner = accounts.groupBy { it.ownerId }
            owners.map { owner ->
                OwnerSummary(
                    owner = owner.toModel(),
                    accounts = accountsByOwner[owner.id].orEmpty().map { it.toModel() },
                )
            }
        }

    override fun observeArchivedOwners(): Flow<List<OwnerSummary>> =
        combine(ownerDao.observeArchived(), accountDao.observeArchived()) { owners, accounts ->
            val accountsByOwner = accounts.groupBy { it.ownerId }
            owners.map { owner ->
                OwnerSummary(
                    owner = owner.toModel(),
                    accounts = accountsByOwner[owner.id].orEmpty().map { it.toModel() },
                )
            }
        }

    override fun observeOwner(ownerId: String): Flow<OwnerDetail?> =
        combine(ownerDao.observeById(ownerId), accountDao.observeForOwner(ownerId)) { owner, accounts ->
            owner?.let {
                val models = accounts.map { account -> account.toModel() }
                OwnerDetail(
                    owner = it.toModel(),
                    activeAccounts = models.filter { account -> !account.archived },
                    archivedAccounts = models.filter { account -> account.archived },
                )
            }
        }

    override suspend fun findOwner(ownerId: String): Owner? =
        ownerDao.findById(ownerId)?.toModel()

    override suspend fun createOwner(nickname: String): Owner {
        val normalized = nickname.trim()
        OwnerValidation.nicknameError(normalized)?.let { throw IllegalArgumentException(it) }

        var attempt = 0
        while (true) {
            try {
                return database.withTransaction {
                    ownerDao.ensureNumberSequence()
                    // The UPDATE takes the write lock, so two transactions can never
                    // observe the same "next" value.
                    ownerDao.incrementNumberSequence()
                    val number = ownerDao.lastAllocatedNumber()
                    val now = timeProvider.now()
                    val entity = OwnerEntity(
                        id = UUID.randomUUID().toString(),
                        number = number,
                        nickname = normalized,
                        createdAt = now,
                        updatedAt = now,
                        archivedAt = null,
                    )
                    ownerDao.insert(entity)
                    entity.toModel()
                }
            } catch (constraint: SQLiteConstraintException) {
                // The unique index on owners.number is the last line of defence.
                attempt += 1
                if (attempt >= MAX_ALLOCATION_ATTEMPTS) throw constraint
            }
        }
    }

    override suspend fun renameOwner(ownerId: String, nickname: String) {
        val normalized = nickname.trim()
        OwnerValidation.nicknameError(normalized)?.let { throw IllegalArgumentException(it) }
        val now = timeProvider.now()
        database.withTransaction {
            ownerDao.findById(ownerId) ?: throw IllegalArgumentException("老板不存在")
            ownerDao.updateNickname(ownerId = ownerId, nickname = normalized, updatedAt = now)
        }
    }

    override suspend fun archiveOwner(ownerId: String) {
        val now = timeProvider.now()
        database.withTransaction {
            val owner = ownerDao.findById(ownerId) ?: throw IllegalArgumentException("老板不存在")
            if (owner.archivedAt == null) {
                ownerDao.archive(ownerId = ownerId, archivedAt = now)
                accountDao.archiveAllForOwner(ownerId = ownerId, archivedAt = now)
            }
        }
    }

    override suspend fun restoreOwner(ownerId: String) {
        val now = timeProvider.now()
        database.withTransaction {
            val owner = ownerDao.findById(ownerId) ?: throw IllegalArgumentException("老板不存在")
            val archivedAt = owner.archivedAt ?: return@withTransaction
            ownerDao.restore(ownerId = ownerId, updatedAt = now)
            accountDao.restoreArchivedWithTimestamp(
                ownerId = ownerId,
                archivedAt = archivedAt,
                updatedAt = now,
            )
        }
    }

    private companion object {
        const val MAX_ALLOCATION_ATTEMPTS = 3
    }
}
