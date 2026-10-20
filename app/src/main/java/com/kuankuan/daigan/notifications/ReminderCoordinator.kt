package com.kuankuan.daigan.notifications

import com.kuankuan.daigan.core.time.ReportingTimeZoneProvider
import com.kuankuan.daigan.core.time.TimeProvider
import com.kuankuan.daigan.data.repository.DatasetGate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single place that decides whether a reminder is posted.
 *
 * It always reads the current persisted settings and the current business data at
 * delivery time. A schedule that was created before an order was completed, renewed,
 * archived, undone or restored therefore cannot post a stale reminder: the eligibility
 * check runs again right before the notification.
 */
@Singleton
class ReminderCoordinator @Inject constructor(
    private val settingsRepository: ReminderSettingsRepository,
    private val reminderRepository: ReminderRepository,
    private val scheduler: ReminderScheduler,
    private val notifier: ReminderNotifier,
    private val timeProvider: TimeProvider,
    private val zoneProvider: ReportingTimeZoneProvider,
) {

    private val scheduleMutex = Mutex()

    fun canNotify(): Boolean = notifier.canNotify()

    fun notificationSettingsIntent() = notifier.notificationSettingsIntent()

    /**
     * Rebuilds the platform schedules from persisted preferences. Safe to call on every
     * start, resume, time-zone change and business change: identities are stable and a
     * repeated call keeps pending work instead of postponing delayed delivery.
     */
    suspend fun refreshSchedule() = DatasetGate.withWrite {
        scheduleMutex.withLock {
            val settings = settingsRepository.load()
            val now = timeProvider.now()
            val eligible = ReminderEvaluator.messages(reminderRepository.loadSnapshot(), now)
            ReminderCategory.entries.filter { !settings.enabled(it) || it !in eligible }.forEach(notifier::cancel)
            scheduler.scheduleAll(settings, now)
        }
    }

    /** Settings changes intentionally replace the old clock; normal refresh keeps it. */
    suspend fun saveSettings(settings: ReminderSettings) = DatasetGate.withWrite {
        scheduleMutex.withLock {
            settingsRepository.save(settings)
            ReminderCategory.entries.filter { !settings.enabled(it) }.forEach(notifier::cancel)
            scheduler.cancelAll()
            scheduler.scheduleAll(settingsRepository.load(), timeProvider.now())
        }
    }

    /** Serialize delivery, de-duplication and settings changes, and exclude restore. */
    suspend fun deliver(categoryKey: String): Boolean = DatasetGate.withWrite {
        scheduleMutex.withLock {
            val category = ReminderCategory.fromKey(categoryKey) ?: return@withLock true
            val now = timeProvider.now()
            val settings = settingsRepository.load()
            if (!settings.enabled(category)) {
                scheduler.cancel(category)
                notifier.cancel(category)
                return@withLock true
            }
            val zone = zoneProvider.zone()
            val day = ReminderTiming.reminderDay(now, zone).toString()
            // A wall-clock change can make an old request fire before today's time.
            val localTime = now.atZone(zone).toLocalTime()
            val minutes = localTime.hour * 60 + localTime.minute
            reminderRepository.withCurrentState {
                if (minutes >= settings.minutes(category) &&
                    !reminderRepository.alreadyDelivered(category, day)
                ) {
                    val snapshot = reminderRepository.loadSnapshot()
                    val message = ReminderEvaluator.messages(snapshot, now)[category]
                    if (message == null) notifier.cancel(category)
                    if (message != null && notifier.post(message)) {
                        reminderRepository.markDelivered(category, day, now)
                    }
                }
            }
            scheduler.scheduleCategory(category, settings.minutes(category), now)
            true
        }
    }

    /**
     * After a dataset replacement the platform work belongs to the old dataset: it is
     * cancelled and rebuilt from the restored preferences and restored business data.
     * Restored work ids are never treated as authoritative state.
     */
    suspend fun onDatasetReplaced() {
        DatasetGate.withWrite {
            scheduleMutex.withLock {
                scheduler.cancelAll()
                ReminderCategory.entries.forEach(notifier::cancel)
                scheduler.scheduleAll(settingsRepository.load(), timeProvider.now())
            }
        }
    }

}
