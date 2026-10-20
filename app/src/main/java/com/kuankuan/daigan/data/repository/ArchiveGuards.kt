package com.kuankuan.daigan.data.repository

import com.kuankuan.daigan.core.time.GameCalendar
import com.kuankuan.daigan.core.time.GameRefreshRules
import com.kuankuan.daigan.data.local.entity.HostingPeriodEntity
import java.time.Instant
import java.time.ZoneId

internal const val ARCHIVED_OWNER_HAS_ACTIVE_WORK_MESSAGE =
    "该老板还有未完成的短期订单或未到期的托管期，请先完成订单或等待托管到期后再归档"

internal const val ARCHIVED_ACCOUNT_HAS_ACTIVE_WORK_MESSAGE =
    "该游戏账号还有未完成的短期订单或未到期的托管期，请先完成订单或等待托管到期后再归档"

/**
 * Archiving hides records from the active lists, so it must not silently hide work.
 * Only the newest period per order decides "unexpired": an old period that was already
 * renewed cannot block archiving on its own.
 */
internal fun assertNoUnexpiredPeriod(
    periods: List<HostingPeriodEntity>,
    now: Instant,
    message: String,
) {
    periods.groupBy { it.orderId }.values
        .mapNotNull { byOrder -> byOrder.maxByOrNull { it.periodIndex } }
        .forEach { period ->
            val rules = GameRefreshRules.fromMinutes(
                zoneId = ZoneId.of(period.gameZoneId),
                dailyRefreshMinutes = period.dailyRefreshMinutes,
                weeklyRefreshDayIso = period.weeklyRefreshDay,
                weeklyRefreshMinutes = period.weeklyRefreshMinutes,
            )
            val today = GameCalendar.gameDate(now, rules)
            require(today.isAfter(period.endDate)) { message }
        }
}
