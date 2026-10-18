package com.daigan.record.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.daigan.record.core.time.GameCalendar
import com.daigan.record.core.time.GameRefreshRules
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.local.dao.GameAccountDao
import com.daigan.record.data.local.dao.OrderDao
import com.daigan.record.data.local.dao.OwnerDao
import com.daigan.record.data.local.entity.HostingPeriodEntity
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

    /**
     * Archives the owner and its currently active accounts in one transaction, recording
     * an explicit archive batch id. Repeated calls are no-ops and never re-stamp the batch.
     */
    suspend fun archiveOwner(ownerId: String)

    /**
     * Restores the owner and exactly the accounts stamped with its archive batch id.
     * Accounts archived independently stay archived. Repeated calls are no-ops.
     */
    suspend fun restoreOwner(ownerId: String)
}

@Singleton
class DefaultOwnerRepository @Inject constructor(
    private val database: RecordDatabase,
    private val ownerDao: OwnerDao,
    private val accountDao: GameAccountDao,
    private val orderDao: OrderDao,
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
                        archiveBatchId = null,
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
            // Idempotent: a second archive must not create a new batch or re-stamp rows,
            // otherwise a later restore would lose the accounts of the original batch.
            if (owner.archivedAt != null) return@withTransaction

            // Do not hide active work behind an archive. Finished short orders and
            // expired periods may be archived; unfinished work must be dealt with first.
            require(orderDao.countUnfinishedShortOrdersForOwner(ownerId) == 0) {
                ARCHIVED_OWNER_HAS_ACTIVE_WORK_MESSAGE
            }
            assertNoUnexpiredPeriod(
                periods = orderDao.findHostingPeriodsForOwner(ownerId),
                now = now,
                message = ARCHIVED_OWNER_HAS_ACTIVE_WORK_MESSAGE,
            )

            // The batch id is the identity of "archived together with its owner".
            // Timestamps are not used for that decision: two independent archives can
            // share a millisecond.
            val batchId = UUID.randomUUID().toString()
            ownerDao.archive(ownerId = ownerId, archivedAt = now, archiveBatchId = batchId)
            accountDao.archiveActiveAccountsForOwner(
                ownerId = ownerId,
                archivedAt = now,
                archiveBatchId = batchId,
            )
        }
    }

    override suspend fun restoreOwner(ownerId: String) {
        val now = timeProvider.now()
        database.withTransaction {
            val owner = ownerDao.findById(ownerId) ?: throw IllegalArgumentException("老板不存在")
            // Idempotent: restoring an active owner changes nothing.
            if (owner.archivedAt == null) return@withTransaction

            // Read the provenance before the restore clears it.
            val batchId = owner.archiveBatchId
            ownerDao.restore(ownerId = ownerId, updatedAt = now)
            // Legacy rows migrated from version 1 have no batch id. Their original
            // provenance cannot be reconstructed, so nothing is restored automatically:
            // every account stays archived and can be restored explicitly by the user.
            if (batchId != null) {
                accountDao.restoreArchivedWithOwnerBatch(
                    ownerId = ownerId,
                    archiveBatchId = batchId,
                    updatedAt = now,
                )
            }
        }
    }

    private companion object {
        const val MAX_ALLOCATION_ATTEMPTS = 3
    }
}
