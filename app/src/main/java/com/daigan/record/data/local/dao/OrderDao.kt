package com.daigan.record.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.daigan.record.data.local.entity.HostingPeriodEntity
import com.daigan.record.data.local.entity.OrderEntity
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Dao
interface OrderDao {

    @Query("SELECT * FROM orders WHERE id = :orderId")
    fun observeById(orderId: String): Flow<OrderEntity?>

    @Query("SELECT * FROM hosting_periods WHERE orderId = :orderId ORDER BY periodIndex DESC")
    fun observePeriodsForOrder(orderId: String): Flow<List<HostingPeriodEntity>>

    @Query("SELECT * FROM orders WHERE id = :orderId")
    suspend fun findById(orderId: String): OrderEntity?

    @Query(
        """
        SELECT o.* FROM orders o
        INNER JOIN game_accounts a ON a.id = o.accountId
        INNER JOIN owners w ON w.id = a.ownerId
        WHERE a.ownerId = :ownerId
        ORDER BY o.createdAt ASC, o.id ASC
        """
    )
    fun observeForOwner(ownerId: String): Flow<List<OrderEntity>>

    /**
     * Unfinished short orders on active accounts. Overdue ordering is calculated with
     * the order's game date in the repository; the SQL order is a stable fallback.
     */
    @Query(
        """
        SELECT o.* FROM orders o
        INNER JOIN game_accounts a ON a.id = o.accountId
        INNER JOIN owners w ON w.id = a.ownerId
        WHERE o.cancelledAt IS NULL AND o.type = 'SHORT_TASK'
          AND o.completedAt IS NULL
          AND a.archivedAt IS NULL
          AND w.archivedAt IS NULL
        ORDER BY
          CASE WHEN o.deadlineDate IS NULL THEN 1 ELSE 0 END ASC,
          o.deadlineDate ASC,
          o.createdAt ASC
        """
    )
    fun observeUnfinishedShortOrders(): Flow<List<OrderEntity>>

    /** Hosting orders on active accounts, including expired periods and completed work. */
    @Query(
        """
        SELECT o.* FROM orders o
        INNER JOIN game_accounts a ON a.id = o.accountId
        INNER JOIN owners w ON w.id = a.ownerId
        WHERE o.cancelledAt IS NULL AND o.type = 'HOSTING'
          AND a.archivedAt IS NULL
          AND w.archivedAt IS NULL
        ORDER BY o.createdAt ASC, o.id ASC
        """
    )
    fun observeActiveHostingOrders(): Flow<List<OrderEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM orders o
        INNER JOIN game_accounts a ON a.id = o.accountId
        WHERE o.cancelledAt IS NULL AND a.ownerId = :ownerId AND o.type = 'SHORT_TASK' AND o.completedAt IS NULL
        """
    )
    suspend fun countUnfinishedShortOrdersForOwner(ownerId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM orders
        WHERE cancelledAt IS NULL AND accountId = :accountId AND type = 'SHORT_TASK' AND completedAt IS NULL
        """
    )
    suspend fun countUnfinishedShortOrdersForAccount(accountId: String): Int

    @Query(
        """
        SELECT p.* FROM hosting_periods p
        INNER JOIN orders o ON o.id = p.orderId
        INNER JOIN game_accounts a ON a.id = o.accountId
        WHERE o.cancelledAt IS NULL AND a.ownerId = :ownerId AND a.archivedAt IS NULL
        """
    )
    suspend fun findHostingPeriodsForOwner(ownerId: String): List<HostingPeriodEntity>

    @Query(
        """
        SELECT p.* FROM hosting_periods p
        INNER JOIN orders o ON o.id = p.orderId
        INNER JOIN game_accounts a ON a.id = o.accountId
        INNER JOIN owners w ON w.id = a.ownerId
        WHERE o.cancelledAt IS NULL AND a.archivedAt IS NULL AND w.archivedAt IS NULL
        ORDER BY p.periodIndex DESC
        """
    )
    fun observePeriodsForActiveHosting(): Flow<List<HostingPeriodEntity>>

    @Query(
        """
        SELECT p.* FROM hosting_periods p
        INNER JOIN orders o ON o.id = p.orderId
        INNER JOIN game_accounts a ON a.id = o.accountId
        WHERE a.ownerId = :ownerId
        ORDER BY p.periodIndex DESC
        """
    )
    fun observePeriodsForOwner(ownerId: String): Flow<List<HostingPeriodEntity>>

    @Query(
        """
        SELECT o.* FROM orders o
        INNER JOIN game_accounts a ON a.id = o.accountId
        INNER JOIN owners w ON w.id = a.ownerId
        WHERE o.cancelledAt IS NULL AND o.type = 'HOSTING' AND a.archivedAt IS NULL AND w.archivedAt IS NULL
        """
    )
    suspend fun findActiveHostingOrders(): List<OrderEntity>

    @Query(
        """
        SELECT p.* FROM hosting_periods p
        INNER JOIN orders o ON o.id = p.orderId
        WHERE o.cancelledAt IS NULL AND o.accountId = :accountId
        """
    )
    suspend fun findHostingPeriodsForAccount(accountId: String): List<HostingPeriodEntity>

    @Insert
    suspend fun insert(order: OrderEntity)

    @Query("UPDATE orders SET completedAt = :completedAt, updatedAt = :updatedAt WHERE id = :orderId")
    suspend fun updateCompletedAt(orderId: String, completedAt: Instant?, updatedAt: Instant)

    @Query("UPDATE orders SET updatedAt = :updatedAt WHERE id = :orderId")
    suspend fun touch(orderId: String, updatedAt: Instant)

    @Query("SELECT COUNT(*) FROM orders WHERE accountId = :accountId")
    suspend fun countForAccount(accountId: String): Int

    @Query("SELECT COUNT(*) FROM orders WHERE accountId = :accountId AND type = :type")
    suspend fun countForAccountAndType(accountId: String, type: String): Int

    @Query("SELECT * FROM orders WHERE accountId = :accountId ORDER BY createdAt ASC")
    suspend fun findForAccount(accountId: String): List<OrderEntity>

    @Query("SELECT COUNT(*) FROM hosting_periods WHERE orderId = :orderId")
    suspend fun countPeriods(orderId: String): Int

    @Query("SELECT MAX(periodIndex) FROM hosting_periods WHERE orderId = :orderId")
    suspend fun maxPeriodIndex(orderId: String): Int?

    @Query("SELECT * FROM hosting_periods WHERE orderId = :orderId ORDER BY periodIndex DESC")
    suspend fun findPeriods(orderId: String): List<HostingPeriodEntity>

    @Query("SELECT * FROM hosting_periods WHERE id = :periodId")
    suspend fun findPeriod(periodId: String): HostingPeriodEntity?

    @Query("SELECT * FROM hosting_periods WHERE orderId = :orderId AND periodIndex = :periodIndex")
    suspend fun findPeriodByIndex(orderId: String, periodIndex: Int): HostingPeriodEntity?

    @Query("SELECT * FROM hosting_periods WHERE renewalOperationId = :operationId")
    suspend fun findPeriodByOperationId(operationId: String): HostingPeriodEntity?

    @Insert
    suspend fun insertPeriod(period: HostingPeriodEntity)

    @Query("SELECT * FROM task_definitions WHERE orderId = :orderId ORDER BY sortOrder ASC, id ASC")
    suspend fun findTasks(orderId: String): List<com.daigan.record.data.local.entity.TaskDefinitionEntity>

    @Query(
        "SELECT * FROM task_occurrences WHERE taskDefinitionId IN " +
            "(SELECT id FROM task_definitions WHERE orderId = :orderId) " +
            "ORDER BY gameDate DESC, recordedAt DESC"
    )
    suspend fun findOccurrences(orderId: String): List<com.daigan.record.data.local.entity.TaskOccurrenceEntity>

    @Query(
        """
        SELECT COUNT(*) FROM task_occurrences occ
        INNER JOIN task_definitions t ON t.id = occ.taskDefinitionId
        WHERE t.orderId = :orderId AND occ.completedAt IS NOT NULL
        """
    )
    suspend fun countCompletedOccurrences(orderId: String): Int

    @Query("SELECT * FROM orders WHERE cancelledAt IS NULL AND type = 'SHORT_TASK' AND completedAt IS NULL")
    suspend fun findUnfinishedShortOrders(): List<OrderEntity>

    @Query("SELECT * FROM orders WHERE cancelledAt IS NULL AND type = 'HOSTING'")
    suspend fun findAllHostingOrders(): List<OrderEntity>

    @Query("SELECT * FROM hosting_periods")
    suspend fun findAllPeriods(): List<HostingPeriodEntity>

    @Query("SELECT * FROM task_definitions WHERE orderId = :orderId AND completedAt IS NULL")
    suspend fun findUnfinishedTasks(orderId: String): List<com.daigan.record.data.local.entity.TaskDefinitionEntity>

    @Query("SELECT * FROM task_definitions")
    suspend fun findAllTasks(): List<com.daigan.record.data.local.entity.TaskDefinitionEntity>

    @Query("SELECT * FROM task_occurrences")
    suspend fun findAllOccurrences(): List<com.daigan.record.data.local.entity.TaskOccurrenceEntity>

    @Query("SELECT * FROM attachments")
    suspend fun findAllAttachments(): List<com.daigan.record.data.local.entity.AttachmentEntity>

    @Query("SELECT * FROM orders WHERE deadlineDate = :date")
    suspend fun findShortOrdersByDeadline(date: LocalDate): List<OrderEntity>

    // ---------------------------------------------------------- statistics reads

    /** Every order, including archived owners/accounts: debt must never disappear. */
    @Query("SELECT * FROM orders ORDER BY createdAt ASC, id ASC")
    fun observeAllOrders(): Flow<List<OrderEntity>>

    @Query("SELECT * FROM hosting_periods ORDER BY periodIndex ASC, id ASC")
    fun observeAllPeriodsFlow(): Flow<List<HostingPeriodEntity>>

    @Query("SELECT * FROM orders")
    suspend fun findAllOrders(): List<OrderEntity>
    @Query("UPDATE hosting_periods SET startDate = :startDate, endDate = :endDate WHERE id = :periodId")
    suspend fun updatePeriodDates(periodId: String, startDate: LocalDate, endDate: LocalDate)

    @Query("UPDATE orders SET deadlineDate = :date, updatedAt = :now WHERE id = :orderId")
    suspend fun updateDeadline(orderId: String, date: LocalDate?, now: Instant)

    @Query("UPDATE orders SET cancelledAt = :now, updatedAt = :now WHERE id = :orderId AND cancelledAt IS NULL")
    suspend fun cancel(orderId: String, now: Instant)

    @Query("SELECT COUNT(*) FROM completion_batch_items WHERE taskDefinitionId IN (SELECT id FROM task_definitions WHERE orderId = :orderId) OR occurrenceId IN (SELECT occ.id FROM task_occurrences occ INNER JOIN task_definitions t ON t.id = occ.taskDefinitionId WHERE t.orderId = :orderId)")
    suspend fun countBatchReferences(orderId: String): Int

    @Query("DELETE FROM order_creation_operations WHERE orderId = :orderId")
    suspend fun deleteCreationIdentity(orderId: String)

    @Query("DELETE FROM task_definitions WHERE orderId = :orderId")
    suspend fun deleteTasks(orderId: String)

    @Query("DELETE FROM hosting_periods WHERE orderId = :orderId")
    suspend fun deletePeriods(orderId: String)

    @Query("DELETE FROM orders WHERE id = :orderId")
    suspend fun deleteOrder(orderId: String)

}
