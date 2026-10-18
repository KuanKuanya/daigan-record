package com.daigan.record.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.daigan.record.data.local.entity.GameAccountEntity
import com.daigan.record.data.model.LoginMethod
import java.time.Instant
import kotlinx.coroutines.flow.Flow

@Dao
interface GameAccountDao {

    // rowid keeps insertion order when two accounts share the same creation instant.
    @Query("SELECT * FROM game_accounts WHERE archivedAt IS NULL ORDER BY createdAt ASC, rowid ASC")
    fun observeActive(): Flow<List<GameAccountEntity>>

    @Query("SELECT * FROM game_accounts WHERE ownerId = :ownerId ORDER BY createdAt ASC, rowid ASC")
    fun observeForOwner(ownerId: String): Flow<List<GameAccountEntity>>

    @Query("SELECT * FROM game_accounts WHERE id = :accountId")
    fun observeById(accountId: String): Flow<GameAccountEntity?>

    @Query("SELECT * FROM game_accounts WHERE archivedAt IS NOT NULL ORDER BY updatedAt DESC, rowid ASC")
    fun observeArchived(): Flow<List<GameAccountEntity>>

    /** All accounts, archived included. Financial history and debt read this flow. */
    @Query("SELECT * FROM game_accounts ORDER BY createdAt ASC, rowid ASC")
    fun observeAll(): Flow<List<GameAccountEntity>>

    @Query("SELECT * FROM game_accounts WHERE id = :accountId")
    suspend fun findById(accountId: String): GameAccountEntity?

    /** Consistent-snapshot read for the financial ledger (archived rows included). */
    @Query("SELECT * FROM game_accounts ORDER BY createdAt ASC, rowid ASC")
    suspend fun findAll(): List<GameAccountEntity>

    @Query("SELECT COUNT(*) FROM game_accounts WHERE ownerId = :ownerId")
    suspend fun countForOwner(ownerId: String): Int

    @Insert
    suspend fun insert(account: GameAccountEntity)

    @Query(
        """
        UPDATE game_accounts SET
            gameName = :gameName,
            server = :server,
            uid = :uid,
            loginAccount = :loginAccount,
            loginMethod = :loginMethod,
            password = :password,
            defaultTemplateId = :defaultTemplateId,
            updatedAt = :updatedAt
        WHERE id = :accountId
        """
    )
    suspend fun update(
        accountId: String,
        gameName: String,
        server: String,
        uid: String,
        loginAccount: String,
        loginMethod: LoginMethod,
        password: String?,
        defaultTemplateId: String?,
        updatedAt: Instant,
    )

    /**
     * Archives the owner's still-active accounts and stamps them with the owner's
     * archive batch. Accounts archived on their own keep their own state and their
     * null batch id, so they are never adopted by the owner archive.
     */
    @Query(
        "UPDATE game_accounts SET archivedAt = :archivedAt, updatedAt = :archivedAt, " +
            "archiveBatchId = :archiveBatchId WHERE ownerId = :ownerId AND archivedAt IS NULL"
    )
    suspend fun archiveActiveAccountsForOwner(
        ownerId: String,
        archivedAt: Instant,
        archiveBatchId: String,
    )

    /**
     * Archives a single account independently. It deliberately stores no batch id:
     * only an owner archive may claim ownership of an archived account.
     */
    @Query(
        "UPDATE game_accounts SET archivedAt = :archivedAt, updatedAt = :archivedAt, " +
            "archiveBatchId = NULL WHERE id = :accountId AND archivedAt IS NULL"
    )
    suspend fun archiveIndependently(accountId: String, archivedAt: Instant)

    /** Restores a single account; already active accounts are untouched. */
    @Query(
        "UPDATE game_accounts SET archivedAt = NULL, archiveBatchId = NULL, updatedAt = :updatedAt " +
            "WHERE id = :accountId AND archivedAt IS NOT NULL"
    )
    suspend fun restoreIndependently(accountId: String, updatedAt: Instant)

    /**
     * Restores exactly the accounts archived in the owner's batch. Accounts archived
     * separately are excluded by the batch id even when their timestamps are identical.
     */
    @Query(
        "UPDATE game_accounts SET archivedAt = NULL, archiveBatchId = NULL, updatedAt = :updatedAt " +
            "WHERE ownerId = :ownerId AND archiveBatchId = :archiveBatchId AND archivedAt IS NOT NULL"
    )
    suspend fun restoreArchivedWithOwnerBatch(
        ownerId: String,
        archiveBatchId: String,
        updatedAt: Instant,
    )
}
