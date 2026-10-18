package com.daigan.record.data.model

import com.daigan.record.core.time.StatisticsRange
import java.time.Instant
import java.time.ZoneId

data class StatisticsBucket(
    val label: String,
    val amountCents: Long,
)

/**
 * Statistics are always derived from stored receipts, orders and periods.
 *
 * Income and order counts honour [range]; [outstandingItems] and [outstandingCents] are
 * deliberately all-time so switching to 本月 can never hide historical debt.
 */
data class Statistics(
    val range: StatisticsRange,
    val zone: ZoneId,
    val label: String,
    val startInclusive: Instant?,
    val endExclusive: Instant?,
    val receivedCents: Long,
    val orderCount: Int,
    val hostingOrderCount: Int,
    val shortOrderCount: Int,
    val outstandingCents: Long,
    val outstandingOrderCount: Int,
    val buckets: List<StatisticsBucket>,
    val outstandingItems: List<OutstandingItem>,
) {
    val hasOrders: Boolean get() = orderCount > 0

    val monthly: Boolean get() = range == StatisticsRange.MONTH

    /** Share of received money in this range versus all outstanding money. */
    val receivedShare: Float
        get() {
            val total = receivedCents + outstandingCents
            return if (total <= 0L) 0f else receivedCents.toFloat() / total.toFloat()
        }

    val hostingShare: Float
        get() = if (orderCount == 0) 0f else hostingOrderCount.toFloat() / orderCount.toFloat()
}
