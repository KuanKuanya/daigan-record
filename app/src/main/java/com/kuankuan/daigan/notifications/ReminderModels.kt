package com.kuankuan.daigan.notifications

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The three approved reminder categories from the design document. No extra category
 * (payments, renewals, owner birthdays…) exists, and none is inferred from data.
 */
enum class ReminderCategory(
    val key: String,
    val label: String,
    val defaultMinutes: Int,
) {
    SHORT_DEADLINE(
        key = "short-deadline",
        label = "短期单截止提醒",
        defaultMinutes = 20 * 60,
    ),
    HOSTING_EXPIRY(
        key = "hosting-expiry",
        label = "托管到期提醒",
        defaultMinutes = 20 * 60,
    ),
    UNFINISHED_TASKS(
        key = "unfinished-tasks",
        label = "未完成任务提醒",
        defaultMinutes = 21 * 60,
    ),
    ;

    companion object {
        fun fromKey(key: String?): ReminderCategory? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Persisted reminder preferences. Times are minutes after midnight of the **device**
 * clock ([ReminderTiming.ZONE_SOURCE] documents the relationship to game time).
 * All switches default to off, so a fresh installation never notifies unprompted.
 */
data class ReminderSettings(
    val shortDeadlineEnabled: Boolean = false,
    val shortDeadlineMinutes: Int = ReminderCategory.SHORT_DEADLINE.defaultMinutes,
    val hostingExpiryEnabled: Boolean = false,
    val hostingExpiryMinutes: Int = ReminderCategory.HOSTING_EXPIRY.defaultMinutes,
    val unfinishedTasksEnabled: Boolean = false,
    val unfinishedTasksMinutes: Int = ReminderCategory.UNFINISHED_TASKS.defaultMinutes,
) {
    fun enabled(category: ReminderCategory): Boolean = when (category) {
        ReminderCategory.SHORT_DEADLINE -> shortDeadlineEnabled
        ReminderCategory.HOSTING_EXPIRY -> hostingExpiryEnabled
        ReminderCategory.UNFINISHED_TASKS -> unfinishedTasksEnabled
    }

    fun minutes(category: ReminderCategory): Int = when (category) {
        ReminderCategory.SHORT_DEADLINE -> shortDeadlineMinutes
        ReminderCategory.HOSTING_EXPIRY -> hostingExpiryMinutes
        ReminderCategory.UNFINISHED_TASKS -> unfinishedTasksMinutes
    }

    fun withEnabled(category: ReminderCategory, enabled: Boolean): ReminderSettings = when (category) {
        ReminderCategory.SHORT_DEADLINE -> copy(shortDeadlineEnabled = enabled)
        ReminderCategory.HOSTING_EXPIRY -> copy(hostingExpiryEnabled = enabled)
        ReminderCategory.UNFINISHED_TASKS -> copy(unfinishedTasksEnabled = enabled)
    }

    fun withMinutes(category: ReminderCategory, minutes: Int): ReminderSettings {
        val normalized = minutes.coerceIn(0, MAX_MINUTES)
        return when (category) {
            ReminderCategory.SHORT_DEADLINE -> copy(shortDeadlineMinutes = normalized)
            ReminderCategory.HOSTING_EXPIRY -> copy(hostingExpiryMinutes = normalized)
            ReminderCategory.UNFINISHED_TASKS -> copy(unfinishedTasksMinutes = normalized)
        }
    }

    val anyEnabled: Boolean
        get() = shortDeadlineEnabled || hostingExpiryEnabled || unfinishedTasksEnabled

    companion object {
        const val MAX_MINUTES = 24 * 60 - 1
    }
}

/** A composed notification. It never contains a password or a login account. */
data class ReminderMessage(
    val category: ReminderCategory,
    val title: String,
    val body: String,
    /** Set only when the message refers to exactly one order, for a safe deep link. */
    val orderId: String?,
)

/**
 * When the reminder clock fires.
 *
 * The user configures a **device local** time of day (for example 20:00). This is when
 * the phone is asked to notify. Whether a reminder is *due* is decided separately from
 * each order's or period's stored game calendar: the game date is computed with the
 * stored zone and refresh rules at that instant. The two are intentionally distinct so
 * changing one does not silently change the other.
 *
 * Delivery is delegated to WorkManager, so it may be delayed by the system. The product
 * copy says so and never promises an exact minute.
 */
object ReminderTiming {

    const val ZONE_SOURCE = "device"

    /** The next instant (strictly after [now]) at which [minutes] occurs in [zone]. */
    fun nextTrigger(now: Instant, minutes: Int, zone: ZoneId): Instant {
        val safeMinutes = minutes.coerceIn(0, ReminderSettings.MAX_MINUTES)
        val time = LocalTime.of(safeMinutes / 60, safeMinutes % 60)
        val today = now.atZone(zone).toLocalDate()
        var candidate = ZonedDateTime.of(today, time, zone).toInstant()
        if (!candidate.isAfter(now)) {
            candidate = ZonedDateTime.of(today.plusDays(1), time, zone).toInstant()
        }
        return candidate
    }

    fun delayMillis(now: Instant, minutes: Int, zone: ZoneId): Long =
        Duration.between(now, nextTrigger(now, minutes, zone)).toMillis().coerceAtLeast(0)

    /** The local day used for the delivery log; independent of game dates. */
    fun reminderDay(now: Instant, zone: ZoneId): LocalDate = now.atZone(zone).toLocalDate()

    fun formatTime(minutes: Int): String {
        val safe = minutes.coerceIn(0, ReminderSettings.MAX_MINUTES)
        return "%02d:%02d".format(safe / 60, safe % 60)
    }

    fun parseTime(text: String): Int? {
        val parts = text.trim().split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
}
