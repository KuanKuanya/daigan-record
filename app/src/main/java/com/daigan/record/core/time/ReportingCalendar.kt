package com.daigan.record.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class StatisticsRange {
    /** Calendar month in the device's current reporting timezone. */
    MONTH,

    /** All recorded history. */
    ALL,
}

/**
 * One bar in the statistics chart. Boundaries are exact instants so a bucket never
 * borrows hours from a neighbouring month, even across DST changes.
 */
data class ReceiptBucket(
    val label: String,
    val startInclusive: Instant,
    val endExclusive: Instant,
)

/**
 * Resolved statistics window for one load. Month boundaries use the device reporting
 * timezone only; game refresh rules do not participate.
 */
data class ReportingWindow(
    val range: StatisticsRange,
    val zone: ZoneId,
    val label: String,
    val startInclusive: Instant?,
    val endExclusive: Instant?,
    val buckets: List<ReceiptBucket>,
)

object ReportingCalendar {

    private val bucketFormatter = DateTimeFormatter.ofPattern("M 月 d 日", Locale.SIMPLIFIED_CHINESE)
    private val monthFormatter = DateTimeFormatter.ofPattern("yyyy 年 M 月", Locale.SIMPLIFIED_CHINESE)
    private val monthKeyFormatter = DateTimeFormatter.ofPattern("yyyy.MM", Locale.SIMPLIFIED_CHINESE)

    /**
     * Builds the range from the current instant in [zone]. For MONTH the window is
     * `[first day 00:00, first day of next month 00:00)`; for ALL the income window is
     * unbounded but the chart still shows the last six calendar months.
     */
    fun window(range: StatisticsRange, zone: ZoneId, now: Instant): ReportingWindow {
        val today = now.atZone(zone).toLocalDate()
        return when (range) {
            StatisticsRange.MONTH -> {
                val first = today.withDayOfMonth(1)
                val next = first.plusMonths(1)
                ReportingWindow(
                    range = range,
                    zone = zone,
                    label = monthFormatter.format(first),
                    startInclusive = first.atStartOfDay(zone).toInstant(),
                    endExclusive = next.atStartOfDay(zone).toInstant(),
                    buckets = monthBuckets(first, zone),
                )
            }

            StatisticsRange.ALL -> {
                val currentMonth = today.withDayOfMonth(1)
                ReportingWindow(
                    range = range,
                    zone = zone,
                    label = "全部累计",
                    startInclusive = null,
                    endExclusive = null,
                    buckets = (5 downTo 0).map { offset ->
                        val month = currentMonth.minusMonths(offset.toLong())
                        bucket(
                            label = monthKeyFormatter.format(month),
                            start = month.atStartOfDay(zone).toInstant(),
                            end = month.plusMonths(1).atStartOfDay(zone).toInstant(),
                        )
                    },
                )
            }
        }
    }

    /** Seven-day buckets inside one calendar month (5 or 6 bars). */
    private fun monthBuckets(first: LocalDate, zone: ZoneId): List<ReceiptBucket> {
        val last = first.plusMonths(1).minusDays(1)
        val buckets = mutableListOf<ReceiptBucket>()
        var cursor = first
        while (!cursor.isAfter(last)) {
            val end = minOf(cursor.plusDays(7), last.plusDays(1))
            val label = if (end == cursor.plusDays(1)) {
                bucketFormatter.format(cursor)
            } else {
                "${cursor.dayOfMonth}–${end.minusDays(1).dayOfMonth} 日"
            }
            buckets += bucket(
                label = label,
                start = cursor.atStartOfDay(zone).toInstant(),
                end = end.atStartOfDay(zone).toInstant(),
            )
            cursor = end
        }
        return buckets
    }

    private fun bucket(label: String, start: Instant, end: Instant) = ReceiptBucket(
        label = label,
        startInclusive = start,
        endExclusive = end,
    )
}
