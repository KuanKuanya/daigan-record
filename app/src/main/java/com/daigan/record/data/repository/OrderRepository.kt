package com.daigan.record.data.repository

import androidx.room.withTransaction
import com.daigan.record.core.time.GameCalendar
import com.daigan.record.core.time.GameRefreshRules
import com.daigan.record.core.time.HostingStatus
import com.daigan.record.core.time.PersistedInstant
import com.daigan.record.core.time.TimeTicker
import com.daigan.record.core.time.nextBoundaryAcross
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.local.dao.AttachmentDao
import com.daigan.record.data.local.dao.CompletionBatchDao
import com.daigan.record.data.local.dao.CreationOperationDao
import com.daigan.record.data.local.dao.GameAccountDao
import com.daigan.record.data.local.dao.OccurrenceDao
import com.daigan.record.data.local.dao.OrderDao
import com.daigan.record.data.local.dao.OwnerDao
import com.daigan.record.data.local.dao.ReceiptDao
import com.daigan.record.data.local.dao.TaskDao
import com.daigan.record.data.local.entity.AttachmentEntity
import com.daigan.record.data.local.entity.CompletionBatchEntity
import com.daigan.record.data.local.entity.CompletionBatchItemEntity
import com.daigan.record.data.local.entity.GameAccountEntity
import com.daigan.record.data.local.entity.HostingPeriodEntity
import com.daigan.record.data.local.entity.OrderCreationOperationEntity
import com.daigan.record.data.local.entity.OrderEntity
import com.daigan.record.data.local.entity.OwnerEntity
import com.daigan.record.data.local.entity.ReceiptCorrectionEntity
import com.daigan.record.data.local.entity.ReceiptEntity
import com.daigan.record.data.local.entity.TaskDefinitionEntity
import com.daigan.record.data.local.entity.TaskOccurrenceEntity
import com.daigan.record.data.local.encodeWeekdays
import com.daigan.record.data.local.toModel
import com.daigan.record.data.model.BatchItemType
import com.daigan.record.data.model.BatchScope
import com.daigan.record.data.model.BulkCompletionResult
import com.daigan.record.data.model.GameRefreshRulesDomain
import com.daigan.record.data.model.HostingOrderDraft
import com.daigan.record.data.model.HostingPeriod
import com.daigan.record.data.model.OccurrenceRules
import com.daigan.record.data.model.OccurrenceSlot
import com.daigan.record.data.model.Order
import com.daigan.record.data.model.OrderCommandFingerprint
import com.daigan.record.data.model.OrderDetail
import com.daigan.record.data.model.OrderSummary
import com.daigan.record.data.model.Money
import com.daigan.record.data.model.OrderTask
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.PaymentRequest
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.RenewalResult
import com.daigan.record.data.model.ShortOrderDraft
import com.daigan.record.data.model.ShortWorkOrder
import com.daigan.record.data.model.ShortWorkTask
import com.daigan.record.data.model.TaskOccurrence
import com.daigan.record.data.model.TodayHostingOrder
import com.daigan.record.data.model.TodayHostingTask
import com.daigan.record.data.model.UndoConflict
import com.daigan.record.data.model.UndoResult
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge

/** User facing reasons shared by repositories and screens. */
const val ARCHIVED_OWNER_ORDER_MESSAGE = "老板已归档，请先恢复老板后再操作订单"
const val ARCHIVED_ACCOUNT_ORDER_MESSAGE = "游戏账号已归档，请先恢复账号后再操作订单"
const val ARCHIVED_OWNER_WORK_MESSAGE = "老板已归档，请先恢复老板后再记录工作"

/**
 * Reusing a create-order operation id with a different normalized payload is a
 * programming/UI error, not a retry: the stored order is returned only when the whole
 * command matches.
 */
const val CREATION_CONFLICT_MESSAGE = "该建单操作标识已被另一份订单使用，请刷新后查看原订单"

/**
 * The occurrence a screen showed is no longer the current one (a daily or weekly
 * boundary was crossed). Callers must refresh and ask again instead of silently
 * writing the action into another game day.
 */
class StaleOccurrenceException(message: String) : IllegalStateException(message)

/**
 * Identities a screen was showing when a hosting bulk action was tapped.
 *
 * The caller passes exactly what the user saw; the repository validates all three
 * levels inside the transaction. If the displayed work is stale the whole operation
 * is rejected with [StaleOccurrenceException] before any row is written, so a batch
 * can never silently complete a different game day's or game week's tasks.
 */
data class DisplayedHostingSnapshot(
    val orderId: String,
    /** The hosting period shown on screen. */
    val periodId: String,
    /** The game date shown on screen (drives daily occurrence windows). */
    val gameDate: LocalDate,
    /** taskId -> occurrence slot key the screen showed for that task today. */
    val occurrenceKeys: Map<String, String>,
)

interface OrderRepository {

    fun observeOrdersForOwner(ownerId: String): Flow<List<OrderSummary>>

    /**
     * One order with its tasks, receipts and correction audit. Every emission is read
     * in a single transaction, so a detail balance always belongs to one database
     * version rather than a mix of independently refreshed DAO flows.
     */
    fun observeOrderDetail(orderId: String): Flow<OrderDetail?>

    /** Active accounts only. Expired and not-yet-started periods are included. */
    fun observeTodayHosting(): Flow<List<TodayHostingOrder>>

    /** Unfinished short orders on active accounts. */
    fun observeTodayShort(): Flow<List<ShortWorkOrder>>

    /**
     * Creates a short order (tasks and optional first receipt) as **one** idempotent
     * command keyed by [ShortOrderDraft.creationOperationId].
     *
     * An identical retry returns the original order id. Reusing the id with a different
     * normalized payload is rejected. Any validation or receipt failure rolls the whole
     * creation back.
     */
    suspend fun createShortOrder(draft: ShortOrderDraft): String

    /** Hosting counterpart of [createShortOrder]; the period is part of the command. */
    suspend fun createHostingOrder(draft: HostingOrderDraft): String

    suspend fun setShortTaskCompleted(taskId: String, completed: Boolean)

    suspend fun updateShortTaskProgress(taskId: String, value: BigDecimal)

    suspend fun completeShortOrder(orderId: String, operationId: String): BulkCompletionResult

    /**
     * Completes/clears the current actionable occurrence of one hosting task.
     *
     * [expectedOccurrenceKey] is the occurrence key the screen displayed. When a
     * boundary was crossed in the meantime the write is rejected with
     * [StaleOccurrenceException] instead of landing on a different game day/window.
     */
    suspend fun setOccurrenceCompleted(
        taskId: String,
        completed: Boolean,
        expectedOccurrenceKey: String? = null,
    )

    suspend fun updateOccurrenceProgress(
        taskId: String,
        value: BigDecimal,
        expectedOccurrenceKey: String? = null,
    )

    /**
     * Completes the currently actionable occurrences of one hosting order.
     *
     * [displayed] is what the screen was showing when the user tapped. It is validated
     * inside the transaction; a stale period, game date or occurrence window rejects
     * the whole operation instead of writing to another game day.
     */
    suspend fun completeHostingOrder(
        orderId: String,
        operationId: String,
        displayed: DisplayedHostingSnapshot,
    ): BulkCompletionResult

    /**
     * Completes the currently actionable occurrences of every active hosting order.
     *
     * [displayed] carries one snapshot per order the screen showed. Only orders the
     * user actually saw are completed; an order missing from the snapshot is skipped.
     * A stale snapshot rejects the whole operation with no partial batch.
     */
    suspend fun completeAllHosting(
        operationId: String,
        displayed: List<DisplayedHostingSnapshot>,
    ): BulkCompletionResult

    suspend fun undoBatch(batchId: String): UndoResult

    /**
     * Backfill/correction for a specific historical game date.
     *
     * Future game dates and weekly windows that have not begun are rejected inside the
     * transaction. [weeklyWindowStart] identifies the exact weekly occurrence when one
     * game day touches two windows; when null the documented midday window is used.
     */
    suspend fun setHistoricalCompletion(
        orderId: String,
        periodId: String,
        taskId: String,
        date: LocalDate,
        completed: Boolean,
        weeklyWindowStart: LocalDate? = null,
    ): Boolean

    suspend fun renewHosting(
        orderId: String,
        operationId: String,
        durationDays: Int,
        priceCents: Long,
    ): RenewalResult
}

/**
 * Captures exactly what the Today page is showing for one order: the period, the game
 * date and each task's occurrence window. Bulk operations validate this snapshot so a
 * refresh boundary crossed between render and tap cannot complete a different day.
 */
fun TodayHostingOrder.toDisplayedSnapshot(): DisplayedHostingSnapshot = DisplayedHostingSnapshot(
    orderId = order.id,
    periodId = period.id,
    gameDate = today,
    occurrenceKeys = tasks.associate { it.task.id to it.slot.key },
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class DefaultOrderRepository @Inject constructor(
    private val database: RecordDatabase,
    private val orderDao: OrderDao,
    private val taskDao: TaskDao,
    private val occurrenceDao: OccurrenceDao,
    private val batchDao: CompletionBatchDao,
    private val attachmentDao: AttachmentDao,
    private val accountDao: GameAccountDao,
    private val ownerDao: OwnerDao,
    private val receiptDao: ReceiptDao,
    private val receiptLedger: ReceiptLedger,
    private val creationOperationDao: CreationOperationDao,
    private val ticker: TimeTicker,
) : OrderRepository {

    private data class AccountOwner(
        val account: GameAccountEntity,
        val owner: OwnerEntity,
    )

    private data class PendingOccurrence(
        val occurrence: TaskOccurrenceEntity,
        val previousCompletedAt: Instant?,
        val previousRecordedAt: Instant?,
        val previousBackfilled: Boolean,
        val previousValue: BigDecimal?,
        val previousBatchId: String?,
        val appliedCompletedAt: Instant,
        val appliedValue: BigDecimal?,
    )

    private fun accountOwnerFlow(): Flow<Map<String, AccountOwner>> =
        combine(accountDao.observeActive(), ownerDao.observeAll()) { accounts, owners ->
            val ownersById = owners.associateBy { it.id }
            accounts.mapNotNull { account ->
                ownersById[account.ownerId]?.let { owner ->
                    account.id to AccountOwner(account, owner)
                }
            }.toMap()
        }

    override fun observeOrdersForOwner(ownerId: String): Flow<List<OrderSummary>> {
        val ownerContext = combine(
            accountDao.observeForOwner(ownerId),
            ownerDao.observeById(ownerId),
        ) { accounts, owner -> accounts.associateBy { it.id } to owner }

        return combine(
            orderDao.observeForOwner(ownerId),
            orderDao.observePeriodsForOwner(ownerId),
            taskDao.observeForOwner(ownerId),
            occurrenceDao.observeForOwner(ownerId),
            ownerContext,
        ) { orders, periods, tasks, occurrences, context ->
            val (accountsById, owner) = context
            if (owner == null) return@combine emptyList()
            orders.mapNotNull { order ->
                val account = accountsById[order.accountId] ?: return@mapNotNull null
                val currentPeriod = periods
                    .filter { it.orderId == order.id }
                    .maxByOrNull { it.periodIndex }
                val orderTasks = tasks.filter { it.orderId == order.id }
                val relevantTaskIds = if (order.type == OrderType.HOSTING) {
                    orderTasks.filter { it.periodId == currentPeriod?.id }.map { it.id }.toSet()
                } else {
                    orderTasks.map { it.id }.toSet()
                }
                val completedCount = if (order.type == OrderType.HOSTING) {
                    occurrences.count { it.taskDefinitionId in relevantTaskIds && it.completedAt != null }
                } else {
                    orderTasks.count { it.completedAt != null }
                }
                OrderSummary(
                    order = order.toModel(),
                    ownerId = owner.id,
                    ownerNumber = owner.toModel().displayNumber,
                    ownerNickname = owner.nickname,
                    accountId = account.id,
                    gameName = account.gameName,
                    server = account.server,
                    currentPeriod = currentPeriod?.toModel(),
                    taskCount = relevantTaskIds.size,
                    completedTaskCount = completedCount,
                )
            }.sortedByDescending { it.order.createdAt }
        }
    }

    private data class OrderDetailSnapshot(
        val order: OrderEntity,
        val owner: OwnerEntity,
        val account: GameAccountEntity,
        val tasks: List<TaskDefinitionEntity>,
        val occurrences: List<TaskOccurrenceEntity>,
        val attachments: List<AttachmentEntity>,
        val periods: List<HostingPeriodEntity>,
        val receipts: List<ReceiptEntity>,
        val corrections: List<ReceiptCorrectionEntity>,
    )

    private data class TodayHostingSnapshot(
        val orders: List<OrderEntity>,
        val periods: List<HostingPeriodEntity>,
        val tasks: List<TaskDefinitionEntity>,
        val occurrences: List<TaskOccurrenceEntity>,
        val context: Map<String, AccountOwner>,
    )

    private data class TodayShortSnapshot(
        val orders: List<OrderEntity>,
        val tasks: List<TaskDefinitionEntity>,
        val context: Map<String, AccountOwner>,
    )

    /**
     * One transactionally consistent per-order read per database change.
     *
     * This used to combine seven independent DAO flows. Each query re-runs on its own
     * schedule, so one commit could be published as a mix of two database versions (for
     * example a new receipt next to the previous period list). The invalidation sources
     * are still Room's own table observations, but the rows are now read inside one
     * `withTransaction` block, like [LedgerSnapshotSource] does for the global ledger.
     * No debounce or delay hides a real inconsistency.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeOrderDetail(orderId: String): Flow<OrderDetail?> =
        merge(
            orderDao.observeById(orderId).map { Unit },
            orderDao.observePeriodsForOrder(orderId).map { Unit },
            taskDao.observeForOrder(orderId).map { Unit },
            occurrenceDao.observeForOrder(orderId).map { Unit },
            attachmentDao.observeForOrder(orderId).map { Unit },
            receiptDao.observeForOrder(orderId).map { Unit },
            receiptDao.observeCorrectionsForOrder(orderId).map { Unit },
            accountDao.observeAll().map { Unit },
            ownerDao.observeAll().map { Unit },
        ).mapLatest {
            database.withDatasetTransaction { readOrderDetail(orderId) }
        }.flatMapLatest { snapshot ->
            if (snapshot == null) {
                flowOf(null)
            } else {
                // Daily/weekly boundaries move "today" and the live weekly window
                // without any database write.
                val rules = buildList {
                    add(refreshOf(snapshot.order))
                    snapshot.periods.forEach { add(refreshOf(it)) }
                }
                ticker.ticks { now -> nextBoundaryAcross(rules, now) }
                    .map { now -> snapshot.toDetail(now) }
            }
        }

    /** Must run inside [observeOrderDetail]'s transaction; every read sees one version. */
    private suspend fun readOrderDetail(orderId: String): OrderDetailSnapshot? {
        val order = orderDao.findById(orderId) ?: return null
        val account = accountDao.findById(order.accountId) ?: return null
        val owner = ownerDao.findById(account.ownerId) ?: return null
        return OrderDetailSnapshot(
            order = order,
            owner = owner,
            account = account,
            tasks = taskDao.findForOrder(orderId),
            occurrences = occurrenceDao.findForOrder(orderId),
            attachments = attachmentDao.findForOrder(orderId),
            periods = orderDao.findPeriods(orderId),
            receipts = receiptDao.findForOrder(orderId),
            corrections = receiptDao.findCorrectionsForOrder(orderId),
        )
    }

    private fun OrderDetailSnapshot.toDetail(now: Instant): OrderDetail = OrderDetail(
        order = order.toModel(),
        owner = owner.toModel(),
        account = account.toModel(),
        tasks = tasks.map { task -> task.toModel() },
        periods = periods.map { period -> period.toModel() },
        occurrences = occurrences.map { it.toModel() },
        attachments = attachments.map { it.toModel() },
        receipts = receipts.map { it.toModel() },
        receiptCorrections = corrections.map { it.toModel() },
        today = GameCalendar.gameDate(now, refreshOf(order)),
        now = now,
    )

    override fun observeTodayHosting(): Flow<List<TodayHostingOrder>> =
        combine(
            orderDao.observeActiveHostingOrders(),
            orderDao.observePeriodsForActiveHosting(),
            taskDao.observeHostingTasksForActiveAccounts(),
            occurrenceDao.observeHostingOccurrencesForActiveAccounts(),
            accountOwnerFlow(),
        ) { orders, periods, tasks, occurrences, context ->
            TodayHostingSnapshot(orders, periods, tasks, occurrences, context)
        }.flatMapLatest { snapshot ->
            val rules = snapshot.periods.map { refreshOf(it) }
            ticker.ticks { now -> nextBoundaryAcross(rules, now) }.map { now ->
                buildTodayHosting(snapshot, now)
            }
        }

    override fun observeTodayShort(): Flow<List<ShortWorkOrder>> =
        combine(
            orderDao.observeUnfinishedShortOrders(),
            taskDao.observeShortTasksForActiveAccounts(),
            accountOwnerFlow(),
        ) { orders, tasks, context ->
            TodayShortSnapshot(orders, tasks, context)
        }.flatMapLatest { snapshot ->
            val rules = snapshot.orders.map { refreshOf(it) }
            ticker.ticks { now -> nextBoundaryAcross(rules, now) }.map { now ->
                buildTodayShort(snapshot, now)
            }
        }

    private fun buildTodayHosting(
        snapshot: TodayHostingSnapshot,
        now: Instant,
    ): List<TodayHostingOrder> = snapshot.orders.mapNotNull { order ->
        val accountOwner = snapshot.context[order.accountId] ?: return@mapNotNull null
        val period = snapshot.periods
            .filter { it.orderId == order.id }
            .maxByOrNull { it.periodIndex }
            ?: return@mapNotNull null
        val rules = refreshOf(period)
        val today = GameCalendar.gameDate(now, rules)
        val status = GameCalendar.hostingStatus(today, period.startDate, period.endDate)
        val periodTasks = snapshot.tasks.filter { it.periodId == period.id }
        val occurrencesByKey = snapshot.occurrences
            .filter { occurrence -> periodTasks.any { it.id == occurrence.taskDefinitionId } }
            .associateBy { it.taskDefinitionId to it.occurrenceKey }

        val todayTasks = if (status == HostingStatus.ACTIVE) {
            periodTasks.mapNotNull { task ->
                val model = task.toModel()
                val slot = OccurrenceRules.slotForNow(
                    recurrence = model.recurrence,
                    weekdays = model.weekdays,
                    everyNDays = model.everyNDays,
                    periodStart = period.startDate,
                    periodEnd = period.endDate,
                    today = today,
                    rules = rules,
                    now = now,
                ) ?: return@mapNotNull null
                val occurrence = occurrencesByKey[task.id to slot.key]
                TodayHostingTask(
                    task = model,
                    occurrenceId = occurrence?.id,
                    slot = slot,
                    completed = occurrence?.completedAt != null,
                    value = occurrence?.value,
                    windowLabel = OccurrenceRules.windowLabel(slot),
                )
            }.sortedBy { it.task.sortOrder }
        } else {
            emptyList()
        }

        TodayHostingOrder(
            order = order.toModel(),
            ownerId = accountOwner.owner.id,
            ownerNumber = accountOwner.owner.toModel().displayNumber,
            ownerNickname = accountOwner.owner.nickname,
            accountId = accountOwner.account.id,
            gameName = accountOwner.account.gameName,
            server = accountOwner.account.server,
            period = period.toModel(),
            today = today,
            status = status,
            tasks = todayTasks,
        )
    }.sortedWith(
        compareBy<TodayHostingOrder>(
            { it.status == HostingStatus.EXPIRED || it.status == HostingStatus.NOT_STARTED },
            { it.allTasksCompleted },
            { it.order.createdAt },
        ),
    )

    private fun buildTodayShort(
        snapshot: TodayShortSnapshot,
        now: Instant,
    ): List<ShortWorkOrder> = snapshot.orders.mapNotNull { order ->
        val accountOwner = snapshot.context[order.accountId] ?: return@mapNotNull null
        val orderTasks = snapshot.tasks.filter { it.orderId == order.id }
        val today = GameCalendar.gameDate(now, refreshOf(order))
        ShortWorkOrder(
            order = order.toModel(),
            ownerId = accountOwner.owner.id,
            ownerNumber = accountOwner.owner.toModel().displayNumber,
            ownerNickname = accountOwner.owner.nickname,
            accountId = accountOwner.account.id,
            gameName = accountOwner.account.gameName,
            server = accountOwner.account.server,
            tasks = orderTasks.map { task ->
                val model = task.toModel()
                ShortWorkTask(
                    task = model,
                    completed = model.completedAt != null,
                    currentValue = model.currentValue,
                )
            }.sortedBy { it.task.sortOrder },
            today = today,
        )
    }.sortedWith(
        compareBy<ShortWorkOrder>(
            { !it.overdue },
            { it.order.deadlineDate ?: LocalDate.MAX },
            { it.order.createdAt },
        ),
    )

    override suspend fun createShortOrder(draft: ShortOrderDraft): String {
        require(draft.creationOperationId.isNotBlank()) { "建单操作标识不能为空" }
        val errors = draft.validate()
        require(errors.isEmpty()) { errors.values.first() }
        val priceCents = requireNotNull(draft.priceCentsOrNull) { "请填写约定总价" }
        val now = PersistedInstant.normalize(ticker.now())
        val rules = draft.refresh ?: GameRefreshRules.provisional(ZoneId.systemDefault())
        val fingerprint = OrderCommandFingerprint.short(draft)

        return createOnce(draft.creationOperationId, OrderType.SHORT_TASK, fingerprint) {
            requireWritableAccount(draft.accountId, draft.ownerId)
            val orderId = UUID.randomUUID().toString()
            orderDao.insert(
                OrderEntity(
                    id = orderId,
                    accountId = draft.accountId,
                    type = OrderType.SHORT_TASK,
                    title = draft.title.trim(),
                    priceCents = priceCents,
                    paymentMode = draft.paymentMode,
                    deadlineDate = draft.deadlineDate,
                    notes = draft.notes.trim().ifBlank { null },
                    gameZoneId = rules.zoneId.id,
                    dailyRefreshMinutes = rules.dailyRefreshMinutes,
                    weeklyRefreshDay = rules.weeklyRefreshDayIso,
                    weeklyRefreshMinutes = rules.weeklyRefreshMinutes,
                    createdAt = now,
                    updatedAt = now,
                    completedAt = null,
                    sourceTemplateId = draft.sourceTemplate?.templateId,
                    sourceTemplateName = draft.sourceTemplate?.gameName,
                    sourceTemplateVersion = draft.sourceTemplate?.templateVersion,
                ),
            )
            draft.tasks.forEachIndexed { index, task ->
                taskDao.insert(
                    task.toEntity(
                        orderId = orderId,
                        periodId = null,
                        sortOrder = index,
                        now = now,
                    ),
                )
            }
            val receiptId = recordInitialPayment(
                orderId = orderId,
                periodId = null,
                draft = draft.initialPayment,
                now = now,
            )
            CreatedOrder(orderId = orderId, receiptId = receiptId)
        }
    }

    override suspend fun createHostingOrder(draft: HostingOrderDraft): String {
        require(draft.creationOperationId.isNotBlank()) { "建单操作标识不能为空" }
        val errors = draft.validate()
        require(errors.isEmpty()) { errors.values.first() }
        val priceCents = requireNotNull(draft.priceCentsOrNull) { "请填写本期约定价格" }
        val now = PersistedInstant.normalize(ticker.now())
        val rules = draft.refresh
        val fingerprint = OrderCommandFingerprint.hosting(draft)

        return createOnce(draft.creationOperationId, OrderType.HOSTING, fingerprint) {
            requireWritableAccount(draft.accountId, draft.ownerId)
            val orderId = UUID.randomUUID().toString()
            val periodId = UUID.randomUUID().toString()
            orderDao.insert(
                OrderEntity(
                    id = orderId,
                    accountId = draft.accountId,
                    type = OrderType.HOSTING,
                    title = draft.title.trim(),
                    priceCents = null,
                    paymentMode = draft.paymentMode,
                    deadlineDate = null,
                    notes = draft.notes.trim().ifBlank { null },
                    gameZoneId = rules.zoneId.id,
                    dailyRefreshMinutes = rules.dailyRefreshMinutes,
                    weeklyRefreshDay = rules.weeklyRefreshDayIso,
                    weeklyRefreshMinutes = rules.weeklyRefreshMinutes,
                    createdAt = now,
                    updatedAt = now,
                    completedAt = null,
                    sourceTemplateId = draft.sourceTemplate?.templateId,
                    sourceTemplateName = draft.sourceTemplate?.gameName,
                    sourceTemplateVersion = draft.sourceTemplate?.templateVersion,
                ),
            )
            orderDao.insertPeriod(
                HostingPeriodEntity(
                    id = periodId,
                    orderId = orderId,
                    periodIndex = 0,
                    startDate = draft.startDate,
                    endDate = draft.endDate,
                    priceCents = priceCents,
                    gameZoneId = rules.zoneId.id,
                    dailyRefreshMinutes = rules.dailyRefreshMinutes,
                    weeklyRefreshDay = rules.weeklyRefreshDayIso,
                    weeklyRefreshMinutes = rules.weeklyRefreshMinutes,
                    createdAt = now,
                    renewalOperationId = null,
                ),
            )
            draft.tasks.forEachIndexed { index, task ->
                taskDao.insert(
                    task.toEntity(
                        orderId = orderId,
                        periodId = periodId,
                        sortOrder = index,
                        now = now,
                    ),
                )
            }
            val receiptId = recordInitialPayment(
                orderId = orderId,
                periodId = periodId,
                draft = draft.initialPayment,
                now = now,
            )
            CreatedOrder(orderId = orderId, receiptId = receiptId)
        }
    }

    private data class CreatedOrder(val orderId: String, val receiptId: String?)

    /**
     * Runs [create] and stores its result under [operationId] in the same transaction.
     *
     * A replay with the same fingerprint returns the stored order. A different payload
     * under an already-used id is rejected. The unique index on the operation id is the
     * final guard: if a concurrent transaction won the race, the losing transaction is
     * rolled back and re-reads the winner's committed result.
     */
    private suspend fun createOnce(
        operationId: String,
        type: OrderType,
        fingerprint: String,
        create: suspend () -> CreatedOrder,
    ): String = DatasetGate.withWrite {
        // The gate is entered before the transaction opens: a dataset replacement waits
        // for this command to finish instead of interleaving with it.
        val attempted = runCatching {
            database.withDatasetTransaction {
                existingCreationOrNull(operationId, type, fingerprint)?.let {
                    return@withDatasetTransaction it.orderId
                }
                val created = create()
                creationOperationDao.insert(
                    OrderCreationOperationEntity(
                        operationId = operationId,
                        orderType = type,
                        fingerprint = fingerprint,
                        orderId = created.orderId,
                        receiptId = created.receiptId,
                        createdAt = PersistedInstant.normalize(ticker.now()),
                    ),
                )
                created.orderId
            }
        }
        attempted.getOrElse { error ->
            // The insert may lose a race against an identical concurrent command. The
            // rollback removed this attempt's order/receipt; return the winner instead.
            val winner = runCatching { creationOperationDao.findByOperationId(operationId) }
                .getOrNull()
            if (winner != null) {
                require(winner.orderType == type && winner.fingerprint == fingerprint) {
                    CREATION_CONFLICT_MESSAGE
                }
                return@getOrElse winner.orderId
            }
            throw error
        }
    }

    private suspend fun existingCreationOrNull(
        operationId: String,
        type: OrderType,
        fingerprint: String,
    ): OrderCreationOperationEntity? {
        val existing = creationOperationDao.findByOperationId(operationId) ?: return null
        require(existing.orderType == type && existing.fingerprint == fingerprint) {
            CREATION_CONFLICT_MESSAGE
        }
        return existing
    }

    /**
     * Optional first receipt, written inside the creation transaction. The receipt uses
     * the user-entered **actual** receipt time (not the recording time) normalized to
     * the persisted precision. Any validation or insert failure aborts the whole
     * transaction, so a rejected payment can never leave a half-created order or period.
     *
     * Returns the created receipt id (or null) so the creation operation can store the
     * complete result of the command.
     */
    private suspend fun recordInitialPayment(
        orderId: String,
        periodId: String?,
        draft: com.daigan.record.data.model.InitialPaymentDraft?,
        now: Instant,
    ): String? {
        if (draft == null) return null
        val amountCents = Money.parsePositiveYuanToCents(draft.amountYuan)
        val receivedAt = draft.receivedAt?.let(PersistedInstant::normalize) ?: now
        val result = receiptLedger.record(
            PaymentRequest(
                orderId = orderId,
                periodId = periodId,
                amountCents = amountCents,
                receivedAt = receivedAt,
                channel = draft.channel,
                note = draft.note,
                operationId = draft.operationId,
            ),
            now = now,
        )
        return result.receipt.id
    }

    override suspend fun setShortTaskCompleted(taskId: String, completed: Boolean) {
        val now = ticker.now()
        database.withDatasetTransaction {
            val task = taskDao.findById(taskId) ?: throw IllegalArgumentException("任务不存在")
            val order = orderDao.findById(task.orderId) ?: throw IllegalArgumentException("订单不存在")
            require(order.type == OrderType.SHORT_TASK) { "只有短期任务可以单独勾选" }
            requireWritableAccount(order.accountId, null)
            val model = task.toModel()
            val newValue = when {
                completed && model.progressType == ProgressType.NUMERIC ->
                    maxOf(model.currentValue, model.targetValue)
                !completed && model.progressType == ProgressType.NUMERIC && model.reachedTarget ->
                    model.startValue
                else -> model.currentValue
            }
            taskDao.updateProgress(
                taskId = taskId,
                value = newValue,
                completedAt = if (completed) now else null,
                batchId = null,
                updatedAt = now,
            )
            refreshShortCompletion(order.id, now)
        }
    }

    override suspend fun updateShortTaskProgress(taskId: String, value: BigDecimal) {
        val now = ticker.now()
        database.withDatasetTransaction {
            val task = taskDao.findById(taskId) ?: throw IllegalArgumentException("任务不存在")
            require(task.progressType == ProgressType.NUMERIC) { "该任务不使用数值进度" }
            val order = orderDao.findById(task.orderId) ?: throw IllegalArgumentException("订单不存在")
            require(order.type == OrderType.SHORT_TASK) { "只有短期任务可以编辑进度" }
            requireWritableAccount(order.accountId, null)
            val reached = value >= task.targetValue
            val completedAt = when {
                reached -> task.completedAt ?: now
                else -> null
            }
            taskDao.updateProgress(
                taskId = taskId,
                value = value,
                completedAt = completedAt,
                batchId = null,
                updatedAt = now,
            )
            refreshShortCompletion(order.id, now)
        }
    }

    override suspend fun completeShortOrder(
        orderId: String,
        operationId: String,
    ): BulkCompletionResult {
        require(operationId.isNotBlank()) { "操作标识不能为空" }
        val now = ticker.now()
        return database.withDatasetTransaction {
            batchDao.findByOperationId(operationId)?.let { existing ->
                return@withDatasetTransaction BulkCompletionResult(
                    batchId = existing.id,
                    completedCount = batchDao.findItems(existing.id).size,
                    skippedCount = 0,
                    alreadyApplied = true,
                )
            }
            val order = orderDao.findById(orderId) ?: throw IllegalArgumentException("订单不存在")
            require(order.type == OrderType.SHORT_TASK) { "只有短期订单可以整单完成" }
            requireWritableAccount(order.accountId, null)
            val tasks = taskDao.findForOrder(orderId)
            val incomplete = tasks.filter { it.completedAt == null }
            val batchId = UUID.randomUUID().toString()
            batchDao.insertBatch(
                CompletionBatchEntity(
                    id = batchId,
                    operationId = operationId,
                    scope = BatchScope.SHORT_ORDER,
                    targetId = orderId,
                    gameDate = null,
                    createdAt = now,
                    undoneAt = null,
                ),
            )
            val items = incomplete.map { task ->
                CompletionBatchItemEntity(
                    id = UUID.randomUUID().toString(),
                    batchId = batchId,
                    itemType = BatchItemType.SHORT_TASK,
                    occurrenceId = null,
                    taskDefinitionId = task.id,
                    previousCompletedAt = task.completedAt,
                    previousRecordedAt = null,
                    previousBackfilled = null,
                    previousValue = task.currentValue,
                    previousCompletionBatchId = task.completionBatchId,
                    appliedCompletedAt = now,
                    appliedRecordedAt = null,
                    appliedBackfilled = null,
                    appliedValue = if (task.progressType == ProgressType.NUMERIC) {
                        maxOf(task.currentValue, task.targetValue)
                    } else {
                        task.currentValue
                    },
                    appliedCompletionBatchId = batchId,
                )
            }
            if (items.isNotEmpty()) batchDao.insertItems(items)
            items.forEach { item ->
                taskDao.updateProgress(
                    taskId = checkNotNull(item.taskDefinitionId),
                    value = checkNotNull(item.appliedValue),
                    completedAt = now,
                    batchId = batchId,
                    updatedAt = now,
                )
            }
            refreshShortCompletion(orderId, now)
            BulkCompletionResult(
                batchId = batchId,
                completedCount = items.size,
                skippedCount = tasks.size - items.size,
                alreadyApplied = false,
            )
        }
    }

    override suspend fun setOccurrenceCompleted(
        taskId: String,
        completed: Boolean,
        expectedOccurrenceKey: String?,
    ) {
        val now = ticker.now()
        database.withDatasetTransaction {
            val task = taskDao.findById(taskId) ?: throw IllegalArgumentException("任务不存在")
            val period = task.periodId?.let { orderDao.findPeriod(it) }
                ?: throw IllegalArgumentException("托管期次不存在")
            val order = orderDao.findById(task.orderId) ?: throw IllegalArgumentException("订单不存在")
            require(order.type == OrderType.HOSTING) { "只有托管任务使用打卡记录" }
            requireWritableAccount(order.accountId, null)
            val rules = refreshOf(period)
            val today = GameCalendar.gameDate(now, rules)
            val model = task.toModel()
            val slot = OccurrenceRules.slotForNow(
                recurrence = model.recurrence,
                weekdays = model.weekdays,
                everyNDays = model.everyNDays,
                periodStart = period.startDate,
                periodEnd = period.endDate,
                today = today,
                rules = rules,
                now = now,
            ) ?: throw IllegalArgumentException("当前没有可完成的托管任务")
            if (expectedOccurrenceKey != null && expectedOccurrenceKey != slot.key) {
                throw StaleOccurrenceException("已跨过刷新时间，当前任务已变为 ${slot.key}，请刷新后重试")
            }
            val occurrence = ensureOccurrence(task, slot, now)
            if (completed) {
                occurrenceDao.updateCompletion(
                    id = occurrence.id,
                    completedAt = now,
                    recordedAt = now,
                    backfilled = false,
                    batchId = null,
                    value = if (model.progressType == ProgressType.NUMERIC) {
                        maxOf(occurrence.value ?: model.startValue, model.targetValue)
                    } else {
                        occurrence.value
                    },
                )
            } else {
                occurrenceDao.updateCompletion(
                    id = occurrence.id,
                    completedAt = null,
                    recordedAt = null,
                    backfilled = false,
                    batchId = null,
                    value = null,
                )
            }
        }
    }

    override suspend fun updateOccurrenceProgress(
        taskId: String,
        value: BigDecimal,
        expectedOccurrenceKey: String?,
    ) {
        val now = ticker.now()
        database.withDatasetTransaction {
            val task = taskDao.findById(taskId) ?: throw IllegalArgumentException("任务不存在")
            require(task.progressType == ProgressType.NUMERIC) { "该任务不使用数值进度" }
            val period = task.periodId?.let { orderDao.findPeriod(it) }
                ?: throw IllegalArgumentException("托管期次不存在")
            val order = orderDao.findById(task.orderId) ?: throw IllegalArgumentException("订单不存在")
            requireWritableAccount(order.accountId, null)
            val rules = refreshOf(period)
            val today = GameCalendar.gameDate(now, rules)
            val model = task.toModel()
            val slot = OccurrenceRules.slotForNow(
                recurrence = model.recurrence,
                weekdays = model.weekdays,
                everyNDays = model.everyNDays,
                periodStart = period.startDate,
                periodEnd = period.endDate,
                today = today,
                rules = rules,
                now = now,
            ) ?: throw IllegalArgumentException("当前没有可记录的托管任务")
            if (expectedOccurrenceKey != null && expectedOccurrenceKey != slot.key) {
                throw StaleOccurrenceException("已跨过刷新时间，当前任务已变为 ${slot.key}，请刷新后重试")
            }
            val occurrence = ensureOccurrence(task, slot, now)
            val reached = value >= model.targetValue
            occurrenceDao.updateCompletion(
                id = occurrence.id,
                completedAt = if (reached) occurrence.completedAt ?: now else null,
                recordedAt = if (reached) occurrence.recordedAt ?: now else null,
                backfilled = false,
                batchId = null,
                value = value,
            )
        }
    }

    override suspend fun completeHostingOrder(
        orderId: String,
        operationId: String,
        displayed: DisplayedHostingSnapshot,
    ): BulkCompletionResult {
        require(operationId.isNotBlank()) { "操作标识不能为空" }
        require(displayed.orderId == orderId) { "展示快照与订单不一致" }
        val now = ticker.now()
        return database.withDatasetTransaction {
            batchDao.findByOperationId(operationId)?.let { existing ->
                return@withDatasetTransaction BulkCompletionResult(
                    batchId = existing.id,
                    completedCount = batchDao.findItems(existing.id).size,
                    skippedCount = 0,
                    alreadyApplied = true,
                )
            }
            val order = orderDao.findById(orderId) ?: throw IllegalArgumentException("订单不存在")
            require(order.type == OrderType.HOSTING) { "只有托管订单可以整单完成" }
            requireWritableAccount(order.accountId, null)
            val period = orderDao.findPeriods(orderId).maxByOrNull { it.periodIndex }
                ?: throw IllegalArgumentException("托管期次不存在")
            assertSnapshotCurrent(displayed, period, now)
            val changes = collectPendingForOrder(period, now)
            commitBatch(
                scope = BatchScope.HOSTING_ORDER,
                targetId = orderId,
                gameDate = GameCalendar.gameDate(now, refreshOf(period)),
                operationId = operationId,
                changes = changes,
                now = now,
            )
        }
    }

    override suspend fun completeAllHosting(
        operationId: String,
        displayed: List<DisplayedHostingSnapshot>,
    ): BulkCompletionResult {
        require(operationId.isNotBlank()) { "操作标识不能为空" }
        val now = ticker.now()
        return database.withDatasetTransaction {
            batchDao.findByOperationId(operationId)?.let { existing ->
                return@withDatasetTransaction BulkCompletionResult(
                    batchId = existing.id,
                    completedCount = batchDao.findItems(existing.id).size,
                    skippedCount = 0,
                    alreadyApplied = true,
                )
            }
            val changes = mutableListOf<PendingOccurrence>()
            orderDao.findActiveHostingOrders().forEach { order ->
                val snapshot = displayed.firstOrNull { it.orderId == order.id } ?: return@forEach
                val period = orderDao.findPeriods(order.id).maxByOrNull { it.periodIndex } ?: return@forEach
                val today = GameCalendar.gameDate(now, refreshOf(period))
                if (GameCalendar.hostingStatus(today, period.startDate, period.endDate) != HostingStatus.ACTIVE) {
                    return@forEach
                }
                assertSnapshotCurrent(snapshot, period, now)
                changes += collectPendingForOrder(period, now)
            }
            commitBatch(
                scope = BatchScope.HOSTING_ALL,
                targetId = null,
                gameDate = null,
                operationId = operationId,
                changes = changes,
                now = now,
            )
        }
    }

    override suspend fun undoBatch(batchId: String): UndoResult {
        val now = ticker.now()
        return database.withDatasetTransaction {
            val batch = batchDao.findById(batchId) ?: throw IllegalArgumentException("找不到该批量操作")
            if (batch.undoneAt != null) {
                return@withDatasetTransaction UndoResult(0, emptyList(), alreadyUndone = true)
            }
            val items = batchDao.findItems(batchId)
            // Undo is a write too: a stale screen must not mutate archived records.
            items.mapNotNull { it.taskDefinitionId }.distinct().forEach { taskId ->
                assertTaskWritable(taskId)
            }
            val conflicts = mutableListOf<UndoConflict>()
            var restored = 0
            val affectedShortOrders = mutableSetOf<String>()

            items.forEach { item ->
                when (item.itemType) {
                    BatchItemType.OCCURRENCE -> {
                        val occurrence = item.occurrenceId?.let { occurrenceDao.findById(it) }
                        if (occurrence == null) {
                            conflicts += UndoConflict(
                                description = "打卡记录",
                                reason = "记录已不存在",
                            )
                            return@forEach
                        }
                        val task = item.taskDefinitionId?.let { taskDao.findById(it) }
                        val label = "${task?.title ?: "打卡记录"} · ${occurrence.gameDate}"
                        val unchanged = occurrence.completedAt == item.appliedCompletedAt &&
                            occurrence.value == item.appliedValue &&
                            occurrence.completionBatchId == item.appliedCompletionBatchId
                        if (!unchanged) {
                            conflicts += UndoConflict(
                                description = label,
                                reason = "该记录在此批量完成后又被修改，已保留最新结果",
                            )
                            return@forEach
                        }
                        occurrenceDao.updateCompletion(
                            id = occurrence.id,
                            completedAt = item.previousCompletedAt,
                            recordedAt = item.previousRecordedAt,
                            backfilled = item.previousBackfilled ?: false,
                            batchId = item.previousCompletionBatchId,
                            value = item.previousValue,
                        )
                        restored += 1
                    }

                    BatchItemType.SHORT_TASK -> {
                        val taskId = item.taskDefinitionId
                        val task = taskId?.let { taskDao.findById(it) }
                        if (task == null) {
                            conflicts += UndoConflict(
                                description = "短期任务",
                                reason = "任务已不存在",
                            )
                            return@forEach
                        }
                        val unchanged = task.completedAt == item.appliedCompletedAt &&
                            task.currentValue == item.appliedValue &&
                            task.completionBatchId == item.appliedCompletionBatchId
                        if (!unchanged) {
                            conflicts += UndoConflict(
                                description = task.title,
                                reason = "该任务在此批量完成后又被修改，已保留最新结果",
                            )
                            return@forEach
                        }
                        taskDao.updateProgress(
                            taskId = task.id,
                            value = item.previousValue ?: task.currentValue,
                            completedAt = item.previousCompletedAt,
                            batchId = item.previousCompletionBatchId,
                            updatedAt = now,
                        )
                        affectedShortOrders += task.orderId
                        restored += 1
                    }
                }
            }

            affectedShortOrders.forEach { orderId -> refreshShortCompletion(orderId, now) }
            batchDao.markUndone(batchId, now)
            UndoResult(restored, conflicts, alreadyUndone = false)
        }
    }

    override suspend fun setHistoricalCompletion(
        orderId: String,
        periodId: String,
        taskId: String,
        date: LocalDate,
        completed: Boolean,
        weeklyWindowStart: LocalDate?,
    ): Boolean {
        val now = ticker.now()
        return database.withDatasetTransaction {
            val order = orderDao.findById(orderId) ?: throw IllegalArgumentException("订单不存在")
            require(order.type == OrderType.HOSTING) { "只有托管订单可以补录" }
            requireWritableAccount(order.accountId, null)
            val period = orderDao.findPeriod(periodId)
                ?: throw IllegalArgumentException("托管期次不存在")
            require(period.orderId == orderId) { "该期次不属于此订单" }
            val task = taskDao.findById(taskId) ?: throw IllegalArgumentException("任务不存在")
            require(task.orderId == orderId && task.periodId == periodId) { "该任务不属于此订单期次" }
            val rules = refreshOf(period)
            val today = GameCalendar.gameDate(now, rules)

            // Temporal validity is enforced before any occurrence row can be created.
            require(!today.isBefore(period.startDate)) { "该托管期次尚未开始，不能记录完成" }
            require(!date.isAfter(today)) { "不能为未来游戏日期补录：$date 尚未开始" }
            require(!date.isBefore(period.startDate)) { "所选日期不在托管期内" }

            val model = task.toModel()
            val slot = if (model.recurrence == RecurrenceType.WEEKLY && weeklyWindowStart != null) {
                OccurrenceRules.slotForWeeklyWindow(
                    windowStart = weeklyWindowStart,
                    date = date,
                    periodStart = period.startDate,
                    periodEnd = period.endDate,
                    rules = rules,
                ) ?: throw IllegalArgumentException("所选游戏周与该日期不匹配")
            } else {
                OccurrenceRules.slotForDate(
                    recurrence = model.recurrence,
                    weekdays = model.weekdays,
                    everyNDays = model.everyNDays,
                    periodStart = period.startDate,
                    periodEnd = period.endDate,
                    date = date,
                    rules = rules,
                ) ?: throw IllegalArgumentException("所选日期没有该任务，或不在托管期内")
            }
            if (model.recurrence == RecurrenceType.WEEKLY) {
                val window = slot.weeklyWindowStart
                    ?.let { GameCalendar.weeklyWindowStartingOn(it, rules) }
                require(window == null || !window.startInstant.isAfter(now)) {
                    "该游戏周尚未开始，不能提前记录"
                }
            }
            val occurrence = ensureOccurrence(task, slot, now)
            val backfilled = date.isBefore(today)
            if (completed) {
                occurrenceDao.updateCompletion(
                    id = occurrence.id,
                    completedAt = now,
                    recordedAt = now,
                    backfilled = backfilled,
                    batchId = null,
                    value = if (model.progressType == ProgressType.NUMERIC) {
                        model.targetValue
                    } else {
                        occurrence.value
                    },
                )
            } else {
                occurrenceDao.updateCompletion(
                    id = occurrence.id,
                    completedAt = null,
                    recordedAt = null,
                    backfilled = backfilled,
                    batchId = null,
                    value = null,
                )
            }
            true
        }
    }

    override suspend fun renewHosting(
        orderId: String,
        operationId: String,
        durationDays: Int,
        priceCents: Long,
    ): RenewalResult {
        require(operationId.isNotBlank()) { "操作标识不能为空" }
        require(durationDays > 0) { "续期天数必须大于 0" }
        require(priceCents >= 0) { "续期价格不能为负数" }
        val now = ticker.now()
        return database.withDatasetTransaction {
            orderDao.findPeriodByOperationId(operationId)?.let { existing ->
                return@withDatasetTransaction RenewalResult(
                    periodId = existing.id,
                    periodIndex = existing.periodIndex,
                    startDate = existing.startDate,
                    endDate = existing.endDate,
                    alreadyApplied = true,
                )
            }
            val order = orderDao.findById(orderId) ?: throw IllegalArgumentException("订单不存在")
            require(order.type == OrderType.HOSTING) { "只有托管订单可以续期" }
            requireWritableAccount(order.accountId, null)
            val current = orderDao.findPeriods(orderId).maxByOrNull { it.periodIndex }
                ?: throw IllegalArgumentException("托管期次不存在")
            val rules = refreshOf(current)
            val today = GameCalendar.gameDate(now, rules)
            require(today.isAfter(current.endDate)) {
                "当前托管期尚未结束，本版本不支持提前续期"
            }
            val nextIndex = current.periodIndex + 1
            orderDao.findPeriodByIndex(orderId, nextIndex)?.let { existing ->
                return@withDatasetTransaction RenewalResult(
                    periodId = existing.id,
                    periodIndex = existing.periodIndex,
                    startDate = existing.startDate,
                    endDate = existing.endDate,
                    alreadyApplied = true,
                )
            }
            val newPeriodId = UUID.randomUUID().toString()
            val endDate = GameCalendar.hostingEndDate(today, durationDays)
            orderDao.insertPeriod(
                HostingPeriodEntity(
                    id = newPeriodId,
                    orderId = orderId,
                    periodIndex = nextIndex,
                    startDate = today,
                    endDate = endDate,
                    priceCents = priceCents,
                    gameZoneId = current.gameZoneId,
                    dailyRefreshMinutes = current.dailyRefreshMinutes,
                    weeklyRefreshDay = current.weeklyRefreshDay,
                    weeklyRefreshMinutes = current.weeklyRefreshMinutes,
                    createdAt = now,
                    renewalOperationId = operationId,
                ),
            )
            taskDao.findForPeriod(current.id).forEachIndexed { index, task ->
                taskDao.insert(
                    task.copy(
                        id = UUID.randomUUID().toString(),
                        periodId = newPeriodId,
                        currentValue = task.startValue,
                        completedAt = null,
                        completionBatchId = null,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
            orderDao.touch(orderId, now)
            RenewalResult(
                periodId = newPeriodId,
                periodIndex = nextIndex,
                startDate = today,
                endDate = endDate,
                alreadyApplied = false,
            )
        }
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun requireWritableAccount(accountId: String, expectedOwnerId: String?): AccountOwner {
        val account = accountDao.findById(accountId)
            ?: throw IllegalArgumentException("游戏账号不存在")
        if (expectedOwnerId != null) {
            require(account.ownerId == expectedOwnerId) { "游戏账号不属于该老板" }
        }
        val owner = ownerDao.findById(account.ownerId)
            ?: throw IllegalArgumentException("老板不存在")
        require(owner.archivedAt == null) { ARCHIVED_OWNER_ORDER_MESSAGE }
        require(account.archivedAt == null) { ARCHIVED_ACCOUNT_ORDER_MESSAGE }
        return AccountOwner(account, owner)
    }

    /**
     * Rejects a write when the task's account or owner is archived. Missing rows are
     * left to the per-item conflict path so undo can still explain them.
     */
    private suspend fun assertTaskWritable(taskId: String) {
        val task = taskDao.findById(taskId) ?: return
        val order = orderDao.findById(task.orderId) ?: return
        val account = accountDao.findById(order.accountId) ?: return
        val owner = ownerDao.findById(account.ownerId) ?: return
        require(owner.archivedAt == null) { ARCHIVED_OWNER_WORK_MESSAGE }
        require(account.archivedAt == null) { ARCHIVED_ACCOUNT_ORDER_MESSAGE }
    }

    private suspend fun refreshShortCompletion(orderId: String, now: Instant) {
        val tasks = taskDao.findForOrder(orderId)
        if (tasks.isEmpty()) return
        val allCompleted = tasks.all { it.completedAt != null }
        val order = orderDao.findById(orderId) ?: return
        val target = if (allCompleted) order.completedAt ?: now else null
        if (order.completedAt != target) {
            orderDao.updateCompletedAt(orderId, target, now)
        }
    }

    private suspend fun collectPendingForOrder(
        period: HostingPeriodEntity,
        now: Instant,
    ): List<PendingOccurrence> {
        val rules = refreshOf(period)
        val today = GameCalendar.gameDate(now, rules)
        if (GameCalendar.hostingStatus(today, period.startDate, period.endDate) != HostingStatus.ACTIVE) {
            return emptyList()
        }
        val changes = mutableListOf<PendingOccurrence>()
        taskDao.findForPeriod(period.id).forEach { taskEntity ->
            val task = taskEntity.toModel()
            val slot = OccurrenceRules.slotForNow(
                recurrence = task.recurrence,
                weekdays = task.weekdays,
                everyNDays = task.everyNDays,
                periodStart = period.startDate,
                periodEnd = period.endDate,
                today = today,
                rules = rules,
                now = now,
            ) ?: return@forEach
            val occurrence = ensureOccurrence(taskEntity, slot, now)
            if (occurrence.completedAt != null) return@forEach
            val appliedValue = if (task.progressType == ProgressType.NUMERIC) {
                maxOf(occurrence.value ?: task.startValue, task.targetValue)
            } else {
                occurrence.value
            }
            changes += PendingOccurrence(
                occurrence = occurrence,
                previousCompletedAt = occurrence.completedAt,
                previousRecordedAt = occurrence.recordedAt,
                previousBackfilled = occurrence.backfilled,
                previousValue = occurrence.value,
                previousBatchId = occurrence.completionBatchId,
                appliedCompletedAt = now,
                appliedValue = appliedValue,
            )
        }
        return changes
    }

    private suspend fun commitBatch(
        scope: BatchScope,
        targetId: String?,
        gameDate: LocalDate?,
        operationId: String,
        changes: List<PendingOccurrence>,
        now: Instant,
    ): BulkCompletionResult {
        val batchId = UUID.randomUUID().toString()
        batchDao.insertBatch(
            CompletionBatchEntity(
                id = batchId,
                operationId = operationId,
                scope = scope,
                targetId = targetId,
                gameDate = gameDate,
                createdAt = now,
                undoneAt = null,
            ),
        )
        if (changes.isNotEmpty()) {
            batchDao.insertItems(
                changes.map { change ->
                    CompletionBatchItemEntity(
                        id = UUID.randomUUID().toString(),
                        batchId = batchId,
                        itemType = BatchItemType.OCCURRENCE,
                        occurrenceId = change.occurrence.id,
                        taskDefinitionId = change.occurrence.taskDefinitionId,
                        previousCompletedAt = change.previousCompletedAt,
                        previousRecordedAt = change.previousRecordedAt,
                        previousBackfilled = change.previousBackfilled,
                        previousValue = change.previousValue,
                        previousCompletionBatchId = change.previousBatchId,
                        appliedCompletedAt = change.appliedCompletedAt,
                        appliedRecordedAt = change.appliedCompletedAt,
                        appliedBackfilled = false,
                        appliedValue = change.appliedValue,
                        appliedCompletionBatchId = batchId,
                    )
                },
            )
            changes.forEach { change ->
                occurrenceDao.updateCompletion(
                    id = change.occurrence.id,
                    completedAt = change.appliedCompletedAt,
                    recordedAt = change.appliedCompletedAt,
                    backfilled = false,
                    batchId = batchId,
                    value = change.appliedValue,
                )
            }
        }
        return BulkCompletionResult(
            batchId = batchId,
            completedCount = changes.size,
            skippedCount = 0,
            alreadyApplied = false,
        )
    }

    private suspend fun ensureOccurrence(
        task: TaskDefinitionEntity,
        slot: OccurrenceSlot,
        now: Instant,
    ): TaskOccurrenceEntity {
        occurrenceDao.findByKey(task.id, slot.key)?.let { return it }
        val entity = TaskOccurrenceEntity(
            id = UUID.randomUUID().toString(),
            taskDefinitionId = task.id,
            occurrenceKey = slot.key,
            gameDate = slot.attributedDate,
            windowStart = slot.windowStart,
            windowEnd = slot.windowEnd,
            completedAt = null,
            recordedAt = null,
            backfilled = false,
            completionBatchId = null,
            value = null,
            createdAt = now,
        )
        occurrenceDao.insertIgnore(entity)
        return occurrenceDao.findByKey(task.id, slot.key) ?: entity
    }

    private fun refreshOf(order: OrderEntity): GameRefreshRules = GameRefreshRules.fromMinutes(
        zoneId = ZoneId.of(order.gameZoneId),
        dailyRefreshMinutes = order.dailyRefreshMinutes,
        weeklyRefreshDayIso = order.weeklyRefreshDay,
        weeklyRefreshMinutes = order.weeklyRefreshMinutes,
    )

    private fun refreshOf(period: HostingPeriodEntity): GameRefreshRules = GameRefreshRules.fromMinutes(
        zoneId = ZoneId.of(period.gameZoneId),
        dailyRefreshMinutes = period.dailyRefreshMinutes,
        weeklyRefreshDayIso = period.weeklyRefreshDay,
        weeklyRefreshMinutes = period.weeklyRefreshMinutes,
    )

    /**
     * Rebuilds the occurrence identities a screen must have been showing for [displayed]
     * to still be current, then compares them. A period change, a crossed daily refresh
     * or a crossed weekly window all reject the write with [StaleOccurrenceException].
     * Runs inside the caller's transaction before any pending change is committed, so a
     * rejection never leaves a partial batch behind.
     */
    private suspend fun assertSnapshotCurrent(
        displayed: DisplayedHostingSnapshot,
        period: HostingPeriodEntity,
        now: Instant,
    ) {
        val rules = refreshOf(period)
        val today = GameCalendar.gameDate(now, rules)
        if (displayed.periodId != period.id) {
            throw StaleOccurrenceException("期次已变化，请刷新后再试")
        }
        if (displayed.gameDate != today) {
            throw StaleOccurrenceException("已跨过每日刷新时间，展示的游戏日已过期")
        }
        if (GameCalendar.hostingStatus(today, period.startDate, period.endDate) != HostingStatus.ACTIVE) {
            throw StaleOccurrenceException("该期次已不在进行中，展示的任务已过期")
        }
        val expected = taskDao.findForPeriod(period.id).mapNotNull { taskEntity ->
            val task = taskEntity.toModel()
            OccurrenceRules.slotForNow(
                recurrence = task.recurrence,
                weekdays = task.weekdays,
                everyNDays = task.everyNDays,
                periodStart = period.startDate,
                periodEnd = period.endDate,
                today = today,
                rules = rules,
                now = now,
            )?.let { taskEntity.id to it.key }
        }.toMap()
        if (displayed.occurrenceKeys != expected) {
            throw StaleOccurrenceException("已跨过刷新时间，展示的任务窗口已过期")
        }
    }

    private fun com.daigan.record.data.model.TaskDraft.toEntity(
        orderId: String,
        periodId: String?,
        sortOrder: Int,
        now: Instant,
    ): TaskDefinitionEntity {
        val start = startDecimal() ?: BigDecimal.ZERO
        val target = targetDecimal() ?: BigDecimal.ONE
        return TaskDefinitionEntity(
            id = UUID.randomUUID().toString(),
            orderId = orderId,
            periodId = periodId,
            title = title.trim(),
            progressType = progressType,
            startValue = start,
            targetValue = target,
            currentValue = start,
            unit = unit.trim().ifBlank { null },
            recurrence = recurrence,
            weekdays = encodeWeekdays(weekdays),
            everyNDays = if (recurrence == com.daigan.record.data.model.RecurrenceType.EVERY_N_DAYS) {
                everyNOrNull()
            } else {
                null
            },
            sortOrder = sortOrder,
            completedAt = null,
            completionBatchId = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun GameRefreshRulesDomain.toCore(): GameRefreshRules = GameRefreshRules.fromMinutes(
        zoneId = zoneId,
        dailyRefreshMinutes = dailyRefreshMinutes,
        weeklyRefreshDayIso = weeklyRefreshDayIso,
        weeklyRefreshMinutes = weeklyRefreshMinutes,
    )
}
