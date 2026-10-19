package com.daigan.record.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.daigan.record.data.local.entity.CompletionBatchEntity
import com.daigan.record.data.local.entity.CompletionBatchItemEntity
import java.time.Instant

@Dao
interface CompletionBatchDao {

    @Insert
    suspend fun insertBatch(batch: CompletionBatchEntity)

    @Insert
    suspend fun insertItems(items: List<CompletionBatchItemEntity>)

    @Query("SELECT * FROM completion_batches WHERE id = :batchId")
    suspend fun findById(batchId: String): CompletionBatchEntity?

    @Query("SELECT * FROM completion_batches WHERE operationId = :operationId")
    suspend fun findByOperationId(operationId: String): CompletionBatchEntity?

    @Query("SELECT * FROM completion_batch_items WHERE batchId = :batchId")
    suspend fun findItems(batchId: String): List<CompletionBatchItemEntity>

    @Query("UPDATE completion_batches SET undoneAt = :undoneAt WHERE id = :batchId")
    suspend fun markUndone(batchId: String, undoneAt: Instant)

    @Query(
        "SELECT * FROM completion_batches WHERE undoneAt IS NULL ORDER BY createdAt DESC LIMIT 1"
    )
    suspend fun latestUndoable(): CompletionBatchEntity?

    @Query("SELECT COUNT(*) FROM completion_batches")
    suspend fun count(): Int

    /** Typed full read used by backup validation (runs every converter). */
    @Query("SELECT * FROM completion_batches")
    suspend fun findAllBatches(): List<CompletionBatchEntity>

    /** Typed full read used by backup validation (runs every converter). */
    @Query("SELECT * FROM completion_batch_items")
    suspend fun findAllItems(): List<CompletionBatchItemEntity>
}
