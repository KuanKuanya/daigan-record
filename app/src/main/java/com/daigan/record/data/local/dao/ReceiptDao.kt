package com.daigan.record.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.daigan.record.data.local.entity.ReceiptCorrectionEntity
import com.daigan.record.data.local.entity.ReceiptEntity
import com.daigan.record.data.model.ReceiptStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceiptDao {

    @Query("SELECT * FROM receipts WHERE orderId = :orderId ORDER BY receivedAt DESC, createdAt DESC, id ASC")
    fun observeForOrder(orderId: String): Flow<List<ReceiptEntity>>

    @Query("SELECT * FROM receipts WHERE orderId = :orderId ORDER BY receivedAt DESC, createdAt DESC")
    suspend fun findForOrder(orderId: String): List<ReceiptEntity>

    @Query("SELECT * FROM receipts WHERE id = :receiptId")
    suspend fun findById(receiptId: String): ReceiptEntity?

    @Query("SELECT * FROM receipts WHERE operationId = :operationId")
    suspend fun findByOperationId(operationId: String): ReceiptEntity?

    @Query("SELECT * FROM receipts ORDER BY receivedAt DESC, createdAt DESC, id ASC")
    fun observeAll(): Flow<List<ReceiptEntity>>

    @Query("SELECT * FROM receipts")
    suspend fun findAll(): List<ReceiptEntity>

    @Insert
    suspend fun insert(receipt: ReceiptEntity)

    /**
     * Moves an ACTIVE receipt to a terminal state. The guard is in SQL so a concurrent
     * correction cannot overwrite a receipt that another transaction already handled.
     */
    @Query(
        "UPDATE receipts SET status = :status, replacedByReceiptId = :replacedByReceiptId " +
            "WHERE id = :receiptId AND status = 'ACTIVE'"
    )
    suspend fun markInactive(
        receiptId: String,
        status: ReceiptStatus,
        replacedByReceiptId: String?,
    ): Int

    @Query(
        "SELECT * FROM receipt_corrections WHERE operationId = :operationId"
    )
    suspend fun findCorrectionByOperationId(operationId: String): ReceiptCorrectionEntity?

    @Query(
        "SELECT * FROM receipt_corrections WHERE receiptId = :receiptId " +
            "ORDER BY createdAt DESC, id ASC"
    )
    suspend fun findCorrectionsForReceipt(receiptId: String): List<ReceiptCorrectionEntity>

    @Query("SELECT * FROM receipt_corrections ORDER BY createdAt DESC, id ASC")
    fun observeAllCorrections(): Flow<List<ReceiptCorrectionEntity>>

    @Query(
        """
        SELECT c.* FROM receipt_corrections c
        INNER JOIN receipts r ON r.id = c.receiptId
        WHERE r.orderId = :orderId
        ORDER BY c.createdAt DESC, c.id ASC
        """
    )
    fun observeCorrectionsForOrder(orderId: String): Flow<List<ReceiptCorrectionEntity>>

    @Query("SELECT * FROM receipt_corrections ORDER BY createdAt DESC, id ASC")
    suspend fun findAllCorrections(): List<ReceiptCorrectionEntity>

    @Insert
    suspend fun insertCorrection(correction: ReceiptCorrectionEntity)
}
