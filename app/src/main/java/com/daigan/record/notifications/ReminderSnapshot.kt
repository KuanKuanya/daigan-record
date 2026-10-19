package com.daigan.record.notifications

import com.daigan.record.core.time.GameCalendar
import com.daigan.record.core.time.GameRefreshRules
import com.daigan.record.data.local.entity.GameAccountEntity
import com.daigan.record.data.local.entity.HostingPeriodEntity
import com.daigan.record.data.local.entity.OrderEntity
import com.daigan.record.data.local.entity.OwnerEntity
import com.daigan.record.data.local.entity.TaskDefinitionEntity
import com.daigan.record.data.local.entity.TaskOccurrenceEntity
import com.daigan.record.data.model.OccurrenceRules
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.RecurrenceType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One task inside a reminder evaluation, reduced to what eligibility needs. */
data class ReminderTask(
    val id: String,
    val title: String,
    val recurrence: RecurrenceType,
    val weekdays: Set<DayOfWeek>,
    val everyNDays: Int?,
    val completed: Boolean,
)

/** One occurrence of a hosting task (only the latest period is relevant). */
data class ReminderOccurrence(
    val taskDefinitionId: String,
    val occurrenceKey: String,
    val completed: Boolean,
)

data class ReminderPeriod(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val refresh: GameRefreshRules,
)

data class ReminderOrder(
    val orderId: String,
    val type: OrderType,
    val title: String,
    val ownerLabel: String,
    val accountLabel: String,
    val archived: Boolean,
    val completed: Boolean,
    val deadlineDate: LocalDate?,
    val refresh: GameRefreshRules,
    val period: ReminderPeriod?,
    val tasks: List<ReminderTask>,
    val occurrences: List<ReminderOccurrence>,
)

data class ReminderSnapshot(val orders: List<ReminderOrder>)

/**
 * Pure reminder eligibility. Every rule is expressed against an explicit [now] and the
 * persisted snapshots, so tests never wait for a real clock.
 *
 * Documented rules (also in docs/reminder-behavior.md):
 *
 * 1. **短期单截止**: a short order that is not completed, has a deadline, and whose
 *    *game date* (stored zone + daily refresh) is on or after the deadline. It is
 *    reported once per day until it is completed or its owner/account is archived.
 *    A completed short order is never reported, even if the deadline passed.
 * 2. **托管到期**: the latest hosting period of an active order whose *game date* is
 *    exactly the period's last day. Expired periods stop reminding (renewal is the
 *    user's decision, not a reminder category), and future periods never remind.
 * 3. **当前应做未完成**: hosting work that is due *today* in the stored game calendar
 *    (daily/weekday/every-N slot for today, or an open weekly window) and has no
 *    completed occurrence, plus short-order tasks that are still unchecked. Historical
 *    backfilled occurrences are always attributed to their own past game date and can
 *    therefore never become a "today" reminder.
 *
 * Archived owners/accounts are excluded from every category: archived records are kept
 * for history, not for nagging.
 */
object ReminderEvaluator {

    fun messages(
        snapshot: ReminderSnapshot,
        now: Instant,
    ): Map<ReminderCategory, ReminderMessage> {
        val result = LinkedHashMap<ReminderCategory, ReminderMessage>()

        val deadlineOrders = snapshot.orders.filter { order ->
            !order.archived && order.type == OrderType.SHORT_TASK && !order.completed &&
                order.deadlineDate != null &&
                !GameCalendar.gameDate(now, order.refresh).isBefore(order.deadlineDate)
        }
        if (deadlineOrders.isNotEmpty()) {
            val overdue = deadlineOrders.count { order ->
                GameCalendar.gameDate(now, order.refresh).isAfter(order.deadlineDate)
            }
            result[ReminderCategory.SHORT_DEADLINE] = ReminderMessage(
                category = ReminderCategory.SHORT_DEADLINE,
                title = if (overdue > 0) {
                    "${deadlineOrders.size} 笔短期单已到截止日期"
                } else {
                    "${deadlineOrders.size} 笔短期单今天截止"
                },
                body = summarize(deadlineOrders.map { order ->
                    "${order.ownerLabel}·${order.title}"
                }),
                orderId = deadlineOrders.singleOrNull()?.orderId,
            )
        }

        val expiring = snapshot.orders.filter { order ->
            !order.archived && order.type == OrderType.HOSTING && order.period != null &&
                GameCalendar.gameDate(now, order.period.refresh) == order.period.endDate
        }
        if (expiring.isNotEmpty()) {
            result[ReminderCategory.HOSTING_EXPIRY] = ReminderMessage(
                category = ReminderCategory.HOSTING_EXPIRY,
                title = "${expiring.size} 笔托管今天到期",
                body = summarize(expiring.map { order ->
                    "${order.ownerLabel}·${order.title}"
                }),
                orderId = expiring.singleOrNull()?.orderId,
            )
        }

        val dueCount = snapshot.orders.sumOf { order ->
            if (order.archived) return@sumOf 0
            when (order.type) {
                OrderType.SHORT_TASK ->
                    if (order.completed) 0 else order.tasks.count { !it.completed }

                OrderType.HOSTING -> dueHostingTaskIds(order, now).size
            }
        }
        if (dueCount > 0) {
            result[ReminderCategory.UNFINISHED_TASKS] = ReminderMessage(
                category = ReminderCategory.UNFINISHED_TASKS,
                title = "还有 $dueCount 项任务没有完成",
                body = "现在应做的任务仍未完成，打开应用即可查看并打卡。",
                orderId = null,
            )
        }
        return result
    }

    /**
     * Ids of hosting tasks that are due at [now] and have no completed occurrence.
     *
     * Uses the same slot rules as the Today screen, so a weekly task stays due for its
     * whole live window and a daily task is due only on its own game date.
     */
    fun dueHostingTaskIds(order: ReminderOrder, now: Instant): Set<String> {
        val period = order.period ?: return emptySet()
        val rules = period.refresh
        val today = GameCalendar.gameDate(now, rules)
        if (today.isBefore(period.startDate) || today.isAfter(period.endDate)) return emptySet()
        val completedKeys = order.occurrences.filter { it.completed }
            .map { it.taskDefinitionId to it.occurrenceKey }
            .toSet()
        return order.tasks.filter { task ->
            val slot = OccurrenceRules.slotForNow(
                recurrence = task.recurrence,
                weekdays = task.weekdays,
                everyNDays = task.everyNDays,
                periodStart = period.startDate,
                periodEnd = period.endDate,
                today = today,
                rules = rules,
                now = now,
            ) ?: return@filter false
            (task.id to slot.key) !in completedKeys
        }.map { it.id }.toSet()
    }

    private fun summarize(labels: List<String>): String {
        val shown = labels.take(3).joinToString("、")
        return if (labels.size > 3) "$shown 等 ${labels.size} 笔" else shown
    }
}

/** Builds [ReminderSnapshot] rows from persisted entities. */
object ReminderSnapshotBuilder {

    fun build(
        orders: List<OrderEntity>,
        accounts: List<GameAccountEntity>,
        owners: List<OwnerEntity>,
        periods: List<HostingPeriodEntity>,
        tasks: List<TaskDefinitionEntity>,
        occurrences: List<TaskOccurrenceEntity>,
    ): ReminderSnapshot {
        val accountsById = accounts.associateBy { it.id }
        val ownersById = owners.associateBy { it.id }
        val periodsById = periods.groupBy { it.orderId }
        val tasksByPeriod = tasks.filter { it.periodId != null }.groupBy { it.periodId }
        val tasksByOrder = tasks.filter { it.periodId == null }.groupBy { it.orderId }
        val occurrencesByTask = occurrences.groupBy { it.taskDefinitionId }

        return ReminderSnapshot(
            orders = orders.mapNotNull { order ->
                val account = accountsById[order.accountId] ?: return@mapNotNull null
                val owner = ownersById[account.ownerId] ?: return@mapNotNull null
                val latestPeriod = periodsById[order.id]?.maxByOrNull { it.periodIndex }
                val periodTasks = latestPeriod?.let { tasksByPeriod[it.id].orEmpty() }.orEmpty()
                val shortTasks = tasksByOrder[order.id].orEmpty()
                ReminderOrder(
                    orderId = order.id,
                    type = order.type,
                    title = order.title,
                    ownerLabel = "#${owner.number} ${owner.nickname}",
                    accountLabel = "${account.gameName}·${account.server}",
                    archived = owner.archivedAt != null || account.archivedAt != null,
                    completed = order.completedAt != null,
                    deadlineDate = order.deadlineDate,
                    refresh = GameRefreshRules.fromMinutes(
                        zoneId = java.time.ZoneId.of(order.gameZoneId),
                        dailyRefreshMinutes = order.dailyRefreshMinutes,
                        weeklyRefreshDayIso = order.weeklyRefreshDay,
                        weeklyRefreshMinutes = order.weeklyRefreshMinutes,
                    ),
                    period = latestPeriod?.let { period ->
                        ReminderPeriod(
                            startDate = period.startDate,
                            endDate = period.endDate,
                            refresh = GameRefreshRules.fromMinutes(
                                zoneId = java.time.ZoneId.of(period.gameZoneId),
                                dailyRefreshMinutes = period.dailyRefreshMinutes,
                                weeklyRefreshDayIso = period.weeklyRefreshDay,
                                weeklyRefreshMinutes = period.weeklyRefreshMinutes,
                            ),
                        )
                    },
                    tasks = (if (order.type == OrderType.HOSTING) periodTasks else shortTasks).map {
                        it.toReminderTask()
                    },
                    occurrences = periodTasks.flatMap { task ->
                        occurrencesByTask[task.id].orEmpty().map { occurrence ->
                            ReminderOccurrence(
                                taskDefinitionId = task.id,
                                occurrenceKey = occurrence.occurrenceKey,
                                completed = occurrence.completedAt != null,
                            )
                        }
                    },
                )
            },
        )
    }

    private fun TaskDefinitionEntity.toReminderTask(): ReminderTask = ReminderTask(
        id = id,
        title = title,
        recurrence = recurrence,
        weekdays = com.daigan.record.data.local.parseWeekdays(weekdays),
        everyNDays = everyNDays,
        completed = completedAt != null,
    )
}

/** Small formatting helpers shared by notifications and tests. */
object ReminderText {
    fun daysOverdue(deadline: LocalDate, gameDate: LocalDate): Long =
        ChronoUnit.DAYS.between(deadline, gameDate)

    fun localDateLabel(date: LocalDate, zone: ZoneId): String =
        date.atStartOfDay(zone).format(ReminderTiming.dateFormatter)
}
