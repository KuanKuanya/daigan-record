package com.daigan.record.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.daigan.record.data.model.OrderType
import java.time.Instant

/**
 * Persistent identity of one "create order" command.
 *
 * The whole creation (order, tasks, hosting period and the optional initial receipt) is
 * one command. Retrying it with the same [operationId] must return the original order
 * instead of creating a second one, while reusing the id with a different normalized
 * payload must be rejected. [fingerprint] is a SHA-256 digest of the normalized request,
 * [orderId] and [receiptId] are the stored result of the committed command.
 *
 * The row is written in the same transaction as the order, so a failure cannot leave a
 * half-created order or a dangling operation result.
 */
@Entity(
    tableName = "order_creation_operations",
    foreignKeys = [
        ForeignKey(
            entity = OrderEntity::class,
            parentColumns = ["id"],
            childColumns = ["orderId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["operationId"], unique = true, name = "index_order_creation_operations_operationId"),
        Index(value = ["orderId"], name = "index_order_creation_operations_orderId"),
    ],
)
data class OrderCreationOperationEntity(
    @PrimaryKey val operationId: String,
    val orderType: OrderType,
    val fingerprint: String,
    val orderId: String,
    /** The optional initial receipt created with the order; null for unpaid creation. */
    val receiptId: String?,
    val createdAt: Instant,
)
