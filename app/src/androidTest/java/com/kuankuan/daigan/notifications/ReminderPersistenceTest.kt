package com.kuankuan.daigan.notifications

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kuankuan.daigan.core.time.ReportingTimeZoneProvider
import com.kuankuan.daigan.testing.OrderTestEnvironment
import com.kuankuan.daigan.testing.shortDraft
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderPersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var env: OrderTestEnvironment
    private lateinit var preferences: ReminderSettingsRepository
    private lateinit var repository: ReminderRepository
    private lateinit var coordinator: ReminderCoordinator
    private val schedule = RecordingScheduler()
    private val notifier = RecordingNotifier()
    @Before fun setup() {
        env = OrderTestEnvironment(context)
        env.clock.set(Instant.parse("2026-10-18T14:00:00Z"))
        preferences = DefaultReminderSettingsRepository(env.database.reminderSettingsDao(), env.clock)
        repository = ReminderRepository(env.database, env.database.orderDao(), env.database.gameAccountDao(), env.database.ownerDao())
        coordinator = ReminderCoordinator(preferences, repository, schedule, notifier, env.clock, ReportingTimeZoneProvider { ZoneId.of("Asia/Shanghai") })
    }
    @After fun close() = env.close()

    @Test fun independentSettingsPersistAndDisablingCancels() = runBlocking {
        val saved = ReminderSettings(true, 65, false, 120, true, 900)
        coordinator.saveSettings(saved)
        assertEquals(saved, DefaultReminderSettingsRepository(env.database.reminderSettingsDao(), env.clock).load())
        assertTrue(schedule.cancelAllCalls > 0)
        coordinator.saveSettings(saved.copy(shortDeadlineEnabled = false, unfinishedTasksEnabled = false))
        assertFalse(schedule.latest!!.anyEnabled)
    }
    @Test fun freshCompletionAndArchivePreventNotificationsFromOldSchedule() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val id = env.orderRepository.createShortOrder(shortDraft(owner.id, account.id, deadline = LocalDate.of(2026, 10, 18)))
        coordinator.saveSettings(ReminderSettings(shortDeadlineEnabled = true))
        env.orderRepository.completeShortOrder(id, "complete")
        coordinator.deliver(ReminderCategory.SHORT_DEADLINE.key)
        assertTrue(notifier.messages.isEmpty())
        val secondId = env.orderRepository.createShortOrder(shortDraft(owner.id, account.id, deadline = LocalDate.of(2026, 10, 18)))
        // The accepted archive contract forbids hiding an account with unfinished work.
        assertTrue(runCatching { env.accountRepository.archiveAccount(account.id) }.exceptionOrNull() is IllegalArgumentException)
        env.orderRepository.completeShortOrder(secondId, "complete-before-archive")
        env.accountRepository.archiveAccount(account.id)
        coordinator.deliver(ReminderCategory.SHORT_DEADLINE.key)
        assertTrue(notifier.messages.isEmpty())
    }
    @Test fun concurrentDeliveryDeduplicatesButNewLocalDayCanNotifyAgain() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.orderRepository.createShortOrder(shortDraft(owner.id, account.id, deadline = LocalDate.of(2026, 10, 18)))
        coordinator.saveSettings(ReminderSettings(shortDeadlineEnabled = true))
        coroutineScope {
            val one = async { coordinator.deliver(ReminderCategory.SHORT_DEADLINE.key) }
            val two = async { coordinator.deliver(ReminderCategory.SHORT_DEADLINE.key) }
            one.await(); two.await()
        }
        assertEquals(1, notifier.messages.size)
        assertTrue(repository.alreadyDelivered(ReminderCategory.SHORT_DEADLINE, "2026-10-18"))
        env.clock.advanceSeconds(86400)
        coordinator.deliver(ReminderCategory.SHORT_DEADLINE.key)
        assertEquals(2, notifier.messages.size)
    }
    @Test fun deniedPermissionDoesNotRecordDeliveryAndClockBeforeConfiguredTimeDoesNotNotify() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.orderRepository.createShortOrder(shortDraft(owner.id, account.id, deadline = LocalDate.of(2026, 10, 18)))
        coordinator.saveSettings(ReminderSettings(shortDeadlineEnabled = true))
        notifier.allowed = false
        coordinator.deliver(ReminderCategory.SHORT_DEADLINE.key)
        assertFalse(repository.alreadyDelivered(ReminderCategory.SHORT_DEADLINE, "2026-10-18"))
        notifier.allowed = true
        env.clock.set(Instant.parse("2026-10-18T10:00:00Z"))
        coordinator.deliver(ReminderCategory.SHORT_DEADLINE.key)
        assertTrue(notifier.messages.isEmpty())
        env.clock.set(Instant.parse("2026-10-18T14:00:00Z"))
        coordinator.deliver(ReminderCategory.SHORT_DEADLINE.key)
        assertEquals(1, notifier.messages.size)
    }
    private class RecordingScheduler : ReminderScheduler {
        var latest: ReminderSettings? = null
        var cancelAllCalls = 0
        override suspend fun scheduleAll(settings: ReminderSettings, now: Instant) { latest = settings }
        override suspend fun scheduleCategory(category: ReminderCategory, minutes: Int, now: Instant) = Unit
        override suspend fun cancel(category: ReminderCategory) = Unit
        override suspend fun cancelAll() { cancelAllCalls++ }
    }
    private class RecordingNotifier : ReminderNotifier {
        var allowed = true
        val messages = mutableListOf<ReminderMessage>()
        override fun canNotify() = allowed
        override fun post(message: ReminderMessage): Boolean { if (!allowed) return false; messages += message; return true }
        override fun notificationSettingsIntent() = Intent()
    }
}
