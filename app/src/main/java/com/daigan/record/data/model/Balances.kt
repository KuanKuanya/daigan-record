package com.daigan.record.data.model

import java.time.LocalDate

/**
 * Balance of one payable item: a short order's whole price, or one hosting period.
 * [paidCents] only counts effective (ACTIVE) receipts, so a void or a superseded row
 * can never inflate the paid amount.
 */
data class PayableBalance(
    val orderId: String,
    /** Null for a short order. */
    val periodId: String?,
    /** Null for a short order. */
    val periodIndex: Int?,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    val priceCents: Long,
    val paidCents: Long,
    val outstandingCents: Long,
) {
    val settled: Boolean get() = outstandingCents == 0L

    val periodLabel: String
        get() = if (periodId == null) {
            "短期订单"
        } else {
            val index = (periodIndex ?: 0) + 1
            if (periodStart != null && periodEnd != null) {
                "第 $index 期 · $periodStart – $periodEnd"
            } else {
                "第 $index 期"
            }
        }
}

/**
 * Derived order-level balances. Everything here is computed from the stored price and
 * effective receipts; no screen keeps its own running total.
 */
data class OrderBalance(
    val orderId: String,
    val orderType: OrderType,
    val paymentMode: PaymentMode,
    /** Current period first for hosting orders; one item for short orders. */
    val items: List<PayableBalance>,
    val totalPriceCents: Long,
    val totalPaidCents: Long,
    val totalOutstandingCents: Long,
    val currentPeriodId: String?,
) {
    val hosting: Boolean get() = orderType == OrderType.HOSTING

    val currentItem: PayableBalance?
        get() = if (hosting) items.firstOrNull { it.periodId == currentPeriodId } else items.firstOrNull()

    /** Debt from every older period; a short order has no separate history. */
    val historicalOutstandingCents: Long
        get() = if (!hosting) 0L else totalOutstandingCents - (currentItem?.outstandingCents ?: 0L)

    val receivableItems: List<PayableBalance> get() = items.filter { !it.settled }
}

/**
 * One outstanding item in the always-all-time collection list. Context is included so
 * the statistics page and payment screens can identify archived debt precisely.
 */
data class OutstandingItem(
    val orderId: String,
    val orderTitle: String,
    val ownerId: String,
    val ownerNumber: String,
    val ownerNickname: String,
    val accountId: String,
    val gameName: String,
    val server: String,
    val orderType: OrderType,
    val periodId: String?,
    val periodIndex: Int?,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    val priceCents: Long,
    val paidCents: Long,
    val outstandingCents: Long,
    /** Sort key: period start for hosting, order creation date for short orders. */
    val sortDate: LocalDate,
) {
    val periodLabel: String
        get() = if (orderType == OrderType.SHORT_TASK) {
            "短期订单"
        } else {
            "第 ${(periodIndex ?: 0) + 1} 期" +
                if (periodStart != null && periodEnd != null) " · $periodStart – $periodEnd" else ""
        }
}

/**
 * Pure balance derivation shared by the order detail, payment entry and statistics.
 * Uses checked arithmetic so a corrupted ledger fails loudly instead of wrapping.
 */
object Balances {

    fun order(
        order: Order,
        periods: List<HostingPeriod>,
        receipts: List<Receipt>,
    ): OrderBalance {
        val effective = receipts.filter { it.effective }
        if (!order.isHosting) {
            val price = order.priceCents ?: 0L
            val paid = Money.sumCents(effective.filter { it.periodId == null }.map { it.amountCents })
            val item = PayableBalance(
                orderId = order.id,
                periodId = null,
                periodIndex = null,
                periodStart = null,
                periodEnd = null,
                priceCents = price,
                paidCents = paid,
                outstandingCents = Money.remainingCents(price, paid),
            )
            return OrderBalance(
                orderId = order.id,
                orderType = order.type,
                paymentMode = order.paymentMode,
                items = listOf(item),
                totalPriceCents = item.priceCents,
                totalPaidCents = item.paidCents,
                totalOutstandingCents = item.outstandingCents,
                currentPeriodId = null,
            )
        }

        val descending = periods.sortedByDescending { it.periodIndex }
        val items = descending.map { period ->
            val paid = Money.sumCents(
                effective.filter { it.periodId == period.id }.map { it.amountCents },
            )
            PayableBalance(
                orderId = order.id,
                periodId = period.id,
                periodIndex = period.periodIndex,
                periodStart = period.startDate,
                periodEnd = period.endDate,
                priceCents = period.priceCents,
                paidCents = paid,
                outstandingCents = Money.remainingCents(period.priceCents, paid),
            )
        }
        return OrderBalance(
            orderId = order.id,
            orderType = order.type,
            paymentMode = order.paymentMode,
            items = items,
            totalPriceCents = Money.sumCents(items.map { it.priceCents }),
            totalPaidCents = Money.sumCents(items.map { it.paidCents }),
            totalOutstandingCents = Money.sumCents(items.map { it.outstandingCents }),
            currentPeriodId = descending.firstOrNull()?.id,
        )
    }
}
