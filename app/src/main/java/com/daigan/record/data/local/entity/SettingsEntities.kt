package com.daigan.record.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * The three approved reminder categories, persisted as one singleton row.
 *
 * Times are minutes after local midnight of the **device** clock. The business dates
 * they refer to (a short-order deadline, a hosting period end, "today's" work) are
 * always computed from the persisted per-order/per-period game calendar, so the clock
 * that says *when* to notify is deliberately separate from the game calendar that says
 * *whether* something is due.
 *
 * Every switch is stored separately and defaults to off, so enabling one category never
 * silently enables another.
 */
@Entity(tableName = "reminder_settings")
data class ReminderSettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val shortDeadlineEnabled: Boolean,
    /** Minutes after local midnight, 0..1439. */
    val shortDeadlineMinutes: Int,
    val hostingExpiryEnabled: Boolean,
    val hostingExpiryMinutes: Int,
    val unfinishedTasksEnabled: Boolean,
    val unfinishedTasksMinutes: Int,
    val updatedAt: Instant,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}

/**
 * Local-only application state. It is deliberately **not** part of a backup: the dataset
 * instance id identifies this installation's current dataset, and a restore must produce
 * a *new* identity so that pending forms from the replaced dataset cannot submit into
 * the restored one.
 *
 * [lastRestoreId] is written inside the same database transaction that replaces all
 * business rows. It is the durable commit point of the restore state machine: on the
 * next start, its presence proves that the database replacement committed, no matter
 * which journal phase was persisted last.
 */
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val datasetInstanceId: String,
    val lastRestoreId: String?,
    val createdAt: Instant,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}

/**
 * One row per (category, local reminder day) that was already delivered.
 *
 * Scheduling is idempotent and the worker re-evaluates current data, so a repeated
 * schedule on the same day must not produce a second notification. The log is
 * reconstructible scheduling state: it is excluded from backups and cleared by a
 * dataset restore, after which reminders are rebuilt from the restored preferences.
 */
@Entity(tableName = "reminder_deliveries", primaryKeys = ["category", "day"])
data class ReminderDeliveryEntity(
    /** Stable category key, see `ReminderCategory.key`. */
    val category: String,
    /** ISO local date of the reminder clock when the notification was posted. */
    val day: String,
    val deliveredAt: Instant,
)
