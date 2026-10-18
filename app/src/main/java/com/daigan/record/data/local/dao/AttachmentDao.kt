package com.daigan.record.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.daigan.record.data.local.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {

    @Query(
        """
        SELECT att.* FROM attachments att
        LEFT JOIN task_occurrences occ ON occ.id = att.occurrenceId
        LEFT JOIN task_definitions t
            ON t.id = COALESCE(att.taskDefinitionId, occ.taskDefinitionId)
        WHERE t.orderId = :orderId
        ORDER BY att.createdAt ASC
        """
    )
    fun observeForOrder(orderId: String): Flow<List<AttachmentEntity>>

    @Query(
        """
        SELECT att.* FROM attachments att
        LEFT JOIN task_occurrences occ ON occ.id = att.occurrenceId
        LEFT JOIN task_definitions t
            ON t.id = COALESCE(att.taskDefinitionId, occ.taskDefinitionId)
        WHERE t.orderId = :orderId
        """
    )
    suspend fun findForOrder(orderId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE id = :attachmentId")
    suspend fun findById(attachmentId: String): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE taskDefinitionId = :taskId")
    suspend fun findForTask(taskId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE occurrenceId = :occurrenceId")
    suspend fun findForOccurrence(occurrenceId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments")
    suspend fun findAll(): List<AttachmentEntity>

    @Insert
    suspend fun insert(attachment: AttachmentEntity)

    @Query("DELETE FROM attachments WHERE id = :attachmentId")
    suspend fun deleteById(attachmentId: String)

    @Query("SELECT COUNT(*) FROM attachments WHERE occurrenceId = :occurrenceId")
    suspend fun countForOccurrence(occurrenceId: String): Int
}
