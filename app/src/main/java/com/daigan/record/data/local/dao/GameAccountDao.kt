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

    @Query("SELECT * FROM game_accounts WHERE archivedAt IS NOT NULL ORDER BY updatedAt DESC, rowid ASC")
    fun observeArchived(): Flow<List<GameAccountEntity>>

    @Query("SELECT * FROM game_accounts WHERE id = :accountId")
    suspend fun findById(accountId: String): GameAccountEntity?

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
        updatedAt: Instant,
    )

    @Query(
        "UPDATE game_accounts SET archivedAt = :archivedAt, updatedAt = :archivedAt " +
            "WHERE ownerId = :ownerId AND archivedAt IS NULL"
    )
    suspend fun archiveAllForOwner(ownerId: String, archivedAt: Instant)

    @Query("UPDATE game_accounts SET archivedAt = :archivedAt, updatedAt = :archivedAt WHERE id = :accountId")
    suspend fun setArchivedAt(accountId: String, archivedAt: Instant)

    @Query("UPDATE game_accounts SET archivedAt = NULL, updatedAt = :updatedAt WHERE id = :accountId")
    suspend fun clearArchivedAt(accountId: String, updatedAt: Instant)

    /**
     * Restores exactly the accounts that were archived together with their owner,
     * identified by the shared archive timestamp.
     */
    @Query(
        "UPDATE game_accounts SET archivedAt = NULL, updatedAt = :updatedAt " +
            "WHERE ownerId = :ownerId AND archivedAt = :archivedAt"
    )
    suspend fun restoreArchivedWithTimestamp(ownerId: String, archivedAt: Instant, updatedAt: Instant)
}
