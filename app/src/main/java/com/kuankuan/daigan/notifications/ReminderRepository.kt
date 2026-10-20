package com.kuankuan.daigan.notifications

import androidx.room.withTransaction
import com.kuankuan.daigan.core.time.TimeProvider
import com.kuankuan.daigan.data.local.RecordDatabase
import com.kuankuan.daigan.data.local.dao.GameAccountDao
import com.kuankuan.daigan.data.local.dao.OrderDao
import com.kuankuan.daigan.data.local.dao.OwnerDao
import com.kuankuan.daigan.data.local.dao.ReminderSettingsDao
import com.kuankuan.daigan.data.local.entity.ReminderSettingsEntity
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Reminder preferences live in Room, not in DataStore: a restore replaces the database
 * and the attachment directory as one operation, so keeping preferences in the same
 * database is the only way to avoid two storage versions after a restore.
 */
interface ReminderSettingsRepository {
    fun observe(): Flow<ReminderSettings>

    suspend fun load(): ReminderSettings

    suspend fun save(settings: ReminderSettings)
}

@Singleton
class DefaultReminderSettingsRepository @Inject constructor(
    private val dao: ReminderSettingsDao,
    private val timeProvider: TimeProvider,
) : ReminderSettingsRepository {

    override fun observe(): Flow<ReminderSettings> = dao.observe().map { it.toSettings() }

    override suspend fun load(): ReminderSettings = dao.find().toSettings()

    override suspend fun save(settings: ReminderSettings) {
        require(ReminderCategory.entries.all { settings.minutes(it) in 0..ReminderSettings.MAX_MINUTES })
        dao.upsert(
            ReminderSettingsEntity(
                id = ReminderSettingsEntity.SINGLETON_ID,
                shortDeadlineEnabled = settings.shortDeadlineEnabled,
                shortDeadlineMinutes = settings.shortDeadlineMinutes,
                hostingExpiryEnabled = settings.hostingExpiryEnabled,
                hostingExpiryMinutes = settings.hostingExpiryMinutes,
                unfinishedTasksEnabled = settings.unfinishedTasksEnabled,
                unfinishedTasksMinutes = settings.unfinishedTasksMinutes,
                updatedAt = timeProvider.now(),
            ),
        )
    }

    private fun ReminderSettingsEntity?.toSettings(): ReminderSettings = if (this == null) {
        ReminderSettings()
    } else {
        ReminderSettings(
            shortDeadlineEnabled = shortDeadlineEnabled,
            shortDeadlineMinutes = shortDeadlineMinutes,
            hostingExpiryEnabled = hostingExpiryEnabled,
            hostingExpiryMinutes = hostingExpiryMinutes,
            unfinishedTasksEnabled = unfinishedTasksEnabled,
            unfinishedTasksMinutes = unfinishedTasksMinutes,
        )
    }
}

/**
 * One consistent read of everything reminder eligibility needs, plus the delivery log
 * used to avoid a second notification on the same day.
 */
@Singleton
class ReminderRepository @Inject constructor(
    private val database: RecordDatabase,
    private val orderDao: OrderDao,
    private val accountDao: GameAccountDao,
    private val ownerDao: OwnerDao,
) {

    suspend fun <T> withCurrentState(block: suspend () -> T): T = database.withTransaction { block() }

    suspend fun loadSnapshot(): ReminderSnapshot = database.withTransaction {
        ReminderSnapshotBuilder.build(
            orders = orderDao.findAllOrders(),
            accounts = accountDao.findAll(),
            owners = ownerDao.findAll(),
            periods = orderDao.findAllPeriods(),
            tasks = orderDao.findAllTasks(),
            occurrences = orderDao.findAllOccurrences(),
        )
    }

    suspend fun alreadyDelivered(category: ReminderCategory, day: String): Boolean =
        database.reminderDeliveryDao().count(category.key, day) > 0

    suspend fun markDelivered(category: ReminderCategory, day: String, now: Instant) {
        database.reminderDeliveryDao().insert(
            com.kuankuan.daigan.data.local.entity.ReminderDeliveryEntity(
                category = category.key,
                day = day,
                deliveredAt = now,
            ),
        )
        // Keep the log tiny: only today and yesterday matter for de-duplication.
        database.reminderDeliveryDao().deleteOlderThan(
            java.time.LocalDate.parse(day).minusDays(2).toString(),
        )
    }

    suspend fun clearDeliveryLog() {
        database.reminderDeliveryDao().deleteAll()
    }

    /** Identity of the current dataset; a restore always produces a new one. */
    suspend fun datasetInstanceId(): String? = database.appSettingsDao().datasetInstanceId()

    /**
     * Coarse "business data changed" signal driven by Room's invalidation tracker. It
     * covers order creation, task changes, completion, undo, renewal, archive changes and
     * a full restore, so the schedule can be recomputed after each of them. Reminders are
     * still re-evaluated from current data when the worker runs.
     */
    fun observeBusinessChanges(): Flow<Unit> = combine(
        combine(
            orderDao.observeAllOrders(), accountDao.observeAll(), ownerDao.observeAll(),
        ) { _, _, _ -> Unit },
        orderDao.observeAllPeriodsFlow(),
        database.taskDao().observeHostingTasksForActiveAccounts(),
        database.taskDao().observeShortTasksForActiveAccounts(),
        database.occurrenceDao().observeHostingOccurrencesForActiveAccounts(),
    ) { _, _, _, _, _ -> Unit }
}
