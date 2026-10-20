package com.kuankuan.daigan.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.kuankuan.daigan.data.local.entity.OrderCreationOperationEntity

@Dao
interface CreationOperationDao {

    @Query("SELECT * FROM order_creation_operations WHERE operationId = :operationId")
    suspend fun findByOperationId(operationId: String): OrderCreationOperationEntity?

    @Query("SELECT * FROM order_creation_operations ORDER BY createdAt ASC, operationId ASC")
    suspend fun findAll(): List<OrderCreationOperationEntity>

    @Insert
    suspend fun insert(operation: OrderCreationOperationEntity)

    @Query("SELECT COUNT(*) FROM order_creation_operations")
    suspend fun count(): Int
}
