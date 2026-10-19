package com.daigan.record.data.repository

import com.daigan.record.core.time.ReportingCalendar
import com.daigan.record.core.time.ReportingWindow
import com.daigan.record.core.time.StatisticsRange
import com.daigan.record.data.local.entity.GameAccountEntity
import com.daigan.record.data.local.entity.OrderEntity
import com.daigan.record.data.local.entity.OwnerEntity
import com.daigan.record.data.local.toModel
import com.daigan.record.data.model.Balances
import com.daigan.record.data.model.Money
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.OutstandingItem
import com.daigan.record.data.model.ReceiptStatus
import com.daigan.record.data.model.Statistics
import com.daigan.record.data.model.StatisticsBucket
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface StatisticsRepository {

    /**
     * Statistics for [range] at [now] in [zone]. The flow re-emits whenever orders,
     * periods or receipts change, so every screen sees the same derived ledger.
     */
    fun observeStatistics(range: StatisticsRange, zone: ZoneId, now: Instant): Flow<Statistics>

    suspend fun loadStatistics(range: StatisticsRange, zone: ZoneId, now: Instant): Statistics
}

@Singleton
class DefaultStatisticsRepository @Inject constructor(
    private val snapshotSource: LedgerSnapshotSource,
) : StatisticsRepository {

    override fun observeStatistics(
        range: StatisticsRange,
        zone: ZoneId,
        now: Instant,
    ): Flow<Statistics> {
        val window = ReportingCalendar.window(range, zone, now)
        return snapshotSource.observe().map { snapshot -> build(snapshot, window) }
    }

    override suspend fun loadStatistics(
        range: StatisticsRange,
        zone: ZoneId,
        now: Instant,
    ): Statistics = observeStatistics(range, zone, now).first()

    private fun build(snapshot: LedgerSnapshot, window: ReportingWindow): Statistics {
        val ordersById = snapshot.orders.associateBy { it.id }
        val accountsById = snapshot.accounts.associateBy { it.id }
        val ownersById = snapshot.owners.associateBy { it.id }
        val periodsByOrder = snapshot.periods.groupBy { it.orderId }
        val receiptsByOrder = snapshot.receipts.groupBy { it.orderId }

        // Income honours the selected range; only effective receipts count.
        val receiptsInRange = snapshot.receipts.filter { receipt ->
            receipt.status == ReceiptStatus.ACTIVE &&
                within(receipt.receivedAt, window.startInclusive, window.endExclusive)
        }
        val receivedCents = Money.sumCents(receiptsInRange.map { it.amountCents })

        val ordersInRange = snapshot.orders.filter { order ->
            order.cancelledAt == null && within(order.createdAt, window.startInclusive, window.endExclusive)
        }
        val hostingCount = ordersInRange.count { it.type == OrderType.HOSTING }
        val shortCount = ordersInRange.count { it.type == OrderType.SHORT_TASK }

        // Outstanding debt is deliberately all-time, independent of the range.
        val outstandingItems = buildList {
            snapshot.orders.forEach { order ->
                val account = accountsById[order.accountId] ?: return@forEach
                val owner = ownersById[account.ownerId] ?: return@forEach
                val periods = periodsByOrder[order.id].orEmpty()
                val receipts = receiptsByOrder[order.id].orEmpty().map { it.toModel() }
                val balance = Balances.order(order.toModel(), periods.map { it.toModel() }, receipts)
                balance.receivableItems.forEach { item ->
                    add(
                        outstandingItem(
                            order = order,
                            account = account,
                            owner = owner,
                            periodId = item.periodId,
                            periodIndex = item.periodIndex,
                            periodStart = item.periodStart,
                            periodEnd = item.periodEnd,
                            priceCents = item.priceCents,
                            paidCents = item.paidCents,
                            outstandingCents = item.outstandingCents,
                            zone = window.zone,
                        ),
                    )
                }
            }
        }.sortedWith(
            compareBy<OutstandingItem> { it.sortDate }
                .thenBy { it.ownerNumber }
                .thenBy { it.orderTitle },
        )

        val outstandingCents = Money.sumCents(outstandingItems.map { it.outstandingCents })
        val buckets = window.buckets.map { bucket ->
            StatisticsBucket(
                label = bucket.label,
                amountCents = Money.sumCents(
                    snapshot.receipts
                        .filter {
                            it.status == ReceiptStatus.ACTIVE &&
                                !it.receivedAt.isBefore(bucket.startInclusive) &&
                                it.receivedAt.isBefore(bucket.endExclusive)
                        }
                        .map { it.amountCents },
                ),
            )
        }

        verifyTotals(snapshot, ordersById)

        return Statistics(
            range = window.range,
            zone = window.zone,
            label = window.label,
            startInclusive = window.startInclusive,
            endExclusive = window.endExclusive,
            receivedCents = receivedCents,
            orderCount = ordersInRange.size,
            hostingOrderCount = hostingCount,
            shortOrderCount = shortCount,
            outstandingCents = outstandingCents,
            outstandingOrderCount = outstandingItems.map { it.orderId }.distinct().size,
            buckets = buckets,
            outstandingItems = outstandingItems,
        )
    }

    private fun outstandingItem(
        order: OrderEntity,
        account: GameAccountEntity,
        owner: OwnerEntity,
        periodId: String?,
        periodIndex: Int?,
        periodStart: java.time.LocalDate?,
        periodEnd: java.time.LocalDate?,
        priceCents: Long,
        paidCents: Long,
        outstandingCents: Long,
        zone: ZoneId,
    ): OutstandingItem = OutstandingItem(
        orderId = order.id,
        orderTitle = order.title,
        ownerId = owner.id,
        ownerNumber = owner.toModel().displayNumber,
        ownerNickname = owner.nickname,
        accountId = account.id,
        gameName = account.gameName,
        server = account.server,
        orderType = order.type,
        periodId = periodId,
        periodIndex = periodIndex,
        periodStart = periodStart,
        periodEnd = periodEnd,
        priceCents = priceCents,
        paidCents = paidCents,
        outstandingCents = outstandingCents,
        sortDate = periodStart ?: order.createdAt.atZone(zone).toLocalDate(),
    )

    private fun within(instant: Instant, start: Instant?, end: Instant?): Boolean {
        if (start != null && instant.isBefore(start)) return false
        if (end != null && !instant.isBefore(end)) return false
        return true
    }

    /**
     * Defensive cross-check: a receipt can never make a payable item negative. Failing
     * loudly here is better than silently displaying a wrong balance.
     */
    private fun verifyTotals(snapshot: LedgerSnapshot, ordersById: Map<String, OrderEntity>) {
        val periodsById = snapshot.periods.associateBy { it.id }
        snapshot.receipts.forEach { receipt ->
            require(ordersById.containsKey(receipt.orderId)) {
                "收款 ${receipt.id} 引用了不存在的订单"
            }
            receipt.periodId?.let { periodId ->
                require(periodsById[periodId]?.orderId == receipt.orderId) {
                    "收款 ${receipt.id} 的期次不属于该订单"
                }
            }
        }
    }
}
