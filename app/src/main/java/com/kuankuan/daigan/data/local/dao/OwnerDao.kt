package com.kuankuan.daigan.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.kuankuan.daigan.data.local.entity.OwnerEntity
import java.time.Instant
import kotlinx.coroutines.flow.Flow

@Dao
interface OwnerDao {

    @Query("SELECT * FROM owners WHERE archivedAt IS NULL ORDER BY number ASC")
    fun observeActive(): Flow<List<OwnerEntity>>

    @Query("SELECT * FROM owners WHERE archivedAt IS NOT NULL ORDER BY number ASC")
    fun observeArchived(): Flow<List<OwnerEntity>>

    @Query("SELECT * FROM owners ORDER BY number ASC")
    fun observeAll(): Flow<List<OwnerEntity>>

    @Query("SELECT * FROM owners WHERE id = :ownerId")
    fun observeById(ownerId: String): Flow<OwnerEntity?>

    @Query("SELECT * FROM owners WHERE id = :ownerId")
    suspend fun findById(ownerId: String): OwnerEntity?

    /** Consistent-snapshot read for the financial ledger (archived rows included). */
    @Query("SELECT * FROM owners ORDER BY number ASC")
    suspend fun findAll(): List<OwnerEntity>

    @Query("SELECT COUNT(*) FROM owners")
    suspend fun count(): Int

    @Insert
    suspend fun insert(owner: OwnerEntity)

    @Query("UPDATE owners SET nickname = :nickname, updatedAt = :updatedAt WHERE id = :ownerId")
    suspend fun updateNickname(ownerId: String, nickname: String, updatedAt: Instant)

    /**
     * Archives the owner and records the archive batch that its accounts share.
     * Already archived owners are left untouched so provenance is never overwritten.
     */
    @Query(
        "UPDATE owners SET archivedAt = :archivedAt, updatedAt = :archivedAt, " +
            "archiveBatchId = :archiveBatchId WHERE id = :ownerId AND archivedAt IS NULL"
    )
    suspend fun archive(ownerId: String, archivedAt: Instant, archiveBatchId: String)

    /** Restores the owner and clears its batch id; already active owners are untouched. */
    @Query(
        "UPDATE owners SET archivedAt = NULL, archiveBatchId = NULL, updatedAt = :updatedAt " +
            "WHERE id = :ownerId AND archivedAt IS NOT NULL"
    )
    suspend fun restore(ownerId: String, updatedAt: Instant)

    /**
     * Number allocation helpers. They must always run inside a single database
     * transaction together with the owner insert: the UPDATE statement takes the
     * write lock, and the unique index on owners.number is the final guard.
     */
    @Query("INSERT OR IGNORE INTO owner_number_sequence (id, nextNumber) VALUES (0, 1)")
    suspend fun ensureNumberSequence()

    @Query("UPDATE owner_number_sequence SET nextNumber = nextNumber + 1 WHERE id = 0")
    suspend fun incrementNumberSequence()

    @Query("SELECT nextNumber - 1 FROM owner_number_sequence WHERE id = 0")
    suspend fun lastAllocatedNumber(): Int
}
