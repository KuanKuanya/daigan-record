package com.daigan.record.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.daigan.record.data.local.entity.TaskDefinitionEntity
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("SELECT * FROM task_definitions WHERE orderId = :orderId ORDER BY sortOrder ASC, id ASC")
    fun observeForOrder(orderId: String): Flow<List<TaskDefinitionEntity>>

    @Query(
        """
        SELECT t.* FROM task_definitions t
        INNER JOIN orders o ON o.id = t.orderId
        INNER JOIN game_accounts a ON a.id = o.accountId
        INNER JOIN owners w ON w.id = a.ownerId
        WHERE o.type = 'HOSTING' AND a.archivedAt IS NULL AND w.archivedAt IS NULL
        ORDER BY t.sortOrder ASC, t.id ASC
        """
    )
    fun observeHostingTasksForActiveAccounts(): Flow<List<TaskDefinitionEntity>>

    @Query(
        """
        SELECT t.* FROM task_definitions t
        INNER JOIN orders o ON o.id = t.orderId
        INNER JOIN game_accounts a ON a.id = o.accountId
        INNER JOIN owners w ON w.id = a.ownerId
        WHERE o.type = 'SHORT_TASK' AND o.completedAt IS NULL
          AND a.archivedAt IS NULL AND w.archivedAt IS NULL
        ORDER BY t.sortOrder ASC, t.id ASC
        """
    )
    fun observeShortTasksForActiveAccounts(): Flow<List<TaskDefinitionEntity>>

    @Query(
        """
        SELECT t.* FROM task_definitions t
        INNER JOIN orders o ON o.id = t.orderId
        INNER JOIN game_accounts a ON a.id = o.accountId
        WHERE a.ownerId = :ownerId
        ORDER BY t.sortOrder ASC, t.id ASC
        """
    )
    fun observeForOwner(ownerId: String): Flow<List<TaskDefinitionEntity>>

    @Query("SELECT * FROM task_definitions WHERE orderId = :orderId ORDER BY sortOrder ASC, id ASC")
    suspend fun findForOrder(orderId: String): List<TaskDefinitionEntity>

    @Query("SELECT * FROM task_definitions WHERE periodId = :periodId ORDER BY sortOrder ASC, id ASC")
    suspend fun findForPeriod(periodId: String): List<TaskDefinitionEntity>

    @Query("SELECT * FROM task_definitions WHERE id = :taskId")
    suspend fun findById(taskId: String): TaskDefinitionEntity?

    @Query("SELECT * FROM task_definitions WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<String>): List<TaskDefinitionEntity>

    @Insert
    suspend fun insert(task: TaskDefinitionEntity)

    @Insert
    suspend fun insertAll(tasks: List<TaskDefinitionEntity>)

    @Query(
        "UPDATE task_definitions SET completedAt = :completedAt, " +
            "completionBatchId = :batchId, updatedAt = :updatedAt WHERE id = :taskId"
    )
    suspend fun updateCompletion(
        taskId: String,
        completedAt: Instant?,
        batchId: String?,
        updatedAt: Instant,
    )

    @Query(
        "UPDATE task_definitions SET currentValue = :value, completedAt = :completedAt, " +
            "completionBatchId = :batchId, updatedAt = :updatedAt WHERE id = :taskId"
    )
    suspend fun updateProgress(
        taskId: String,
        value: BigDecimal,
        completedAt: Instant?,
        batchId: String?,
        updatedAt: Instant,
    )

    @Query(
        "UPDATE task_definitions SET currentValue = :value, completedAt = :completedAt, " +
            "updatedAt = :updatedAt WHERE id = :taskId"
    )
    suspend fun updateProgressKeepingBatch(
        taskId: String,
        value: BigDecimal,
        completedAt: Instant?,
        updatedAt: Instant,
    )
}
