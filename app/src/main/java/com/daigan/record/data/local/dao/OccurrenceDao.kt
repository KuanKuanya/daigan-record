package com.daigan.record.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.daigan.record.data.local.entity.TaskOccurrenceEntity
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Dao
interface OccurrenceDao {

    @Query(
        """
        SELECT occ.* FROM task_occurrences occ
        INNER JOIN task_definitions t ON t.id = occ.taskDefinitionId
        WHERE t.orderId = :orderId
        ORDER BY occ.gameDate DESC, occ.recordedAt DESC
        """
    )
    fun observeForOrder(orderId: String): Flow<List<TaskOccurrenceEntity>>

    /** Same rows and order as [observeForOrder], for one consistent detail read. */
    @Query(
        """
        SELECT occ.* FROM task_occurrences occ
        INNER JOIN task_definitions t ON t.id = occ.taskDefinitionId
        WHERE t.orderId = :orderId
        ORDER BY occ.gameDate DESC, occ.recordedAt DESC
        """
    )
    suspend fun findForOrder(orderId: String): List<TaskOccurrenceEntity>

    @Query(
        """
        SELECT occ.* FROM task_occurrences occ
        INNER JOIN task_definitions t ON t.id = occ.taskDefinitionId
        INNER JOIN orders o ON o.id = t.orderId
        INNER JOIN game_accounts a ON a.id = o.accountId
        INNER JOIN owners w ON w.id = a.ownerId
        WHERE o.type = 'HOSTING' AND a.archivedAt IS NULL AND w.archivedAt IS NULL
        """
    )
    fun observeHostingOccurrencesForActiveAccounts(): Flow<List<TaskOccurrenceEntity>>

    @Query(
        "SELECT * FROM task_occurrences WHERE taskDefinitionId = :taskId AND occurrenceKey = :key"
    )
    suspend fun findByKey(taskId: String, key: String): TaskOccurrenceEntity?

    @Query(
        """
        SELECT occ.* FROM task_occurrences occ
        INNER JOIN task_definitions t ON t.id = occ.taskDefinitionId
        INNER JOIN orders o ON o.id = t.orderId
        INNER JOIN game_accounts a ON a.id = o.accountId
        WHERE a.ownerId = :ownerId
        """
    )
    fun observeForOwner(ownerId: String): Flow<List<TaskOccurrenceEntity>>

    @Query("SELECT * FROM task_occurrences WHERE id = :id")
    suspend fun findById(id: String): TaskOccurrenceEntity?

    @Query(
        "SELECT * FROM task_occurrences WHERE taskDefinitionId = :taskId " +
            "ORDER BY gameDate DESC, recordedAt DESC"
    )
    suspend fun findForTask(taskId: String): List<TaskOccurrenceEntity>

    @Query(
        "SELECT * FROM task_occurrences WHERE taskDefinitionId = :taskId " +
            "AND gameDate = :gameDate ORDER BY recordedAt DESC"
    )
    suspend fun findForTaskOnDate(taskId: String, gameDate: LocalDate): List<TaskOccurrenceEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(occurrence: TaskOccurrenceEntity): Long

    @Query(
        "UPDATE task_occurrences SET completedAt = :completedAt, recordedAt = :recordedAt, " +
            "backfilled = :backfilled, completionBatchId = :batchId, value = :value " +
            "WHERE id = :id"
    )
    suspend fun updateCompletion(
        id: String,
        completedAt: Instant?,
        recordedAt: Instant?,
        backfilled: Boolean,
        batchId: String?,
        value: BigDecimal?,
    )

    @Query("UPDATE task_occurrences SET value = :value WHERE id = :id")
    suspend fun updateValue(id: String, value: BigDecimal)

    @Query("SELECT * FROM task_occurrences WHERE taskDefinitionId IN (:taskIds)")
    suspend fun findForTasks(taskIds: List<String>): List<TaskOccurrenceEntity>

    @Query("SELECT * FROM task_occurrences")
    suspend fun findAll(): List<TaskOccurrenceEntity>

    @Query(
        """
        SELECT COUNT(*) FROM task_occurrences occ
        INNER JOIN task_definitions t ON t.id = occ.taskDefinitionId
        WHERE t.orderId = :orderId AND occ.completedAt IS NOT NULL
        """
    )
    suspend fun countCompletedForOrder(orderId: String): Int
}
