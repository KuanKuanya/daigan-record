package com.kuankuan.daigan.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.WorkManager
import java.time.Instant
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderWorkManagerTest {
    @Test fun repeatedRefreshKeepsOnePendingWorkAndDisableCancelsIt() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        val scheduler = WorkManagerReminderScheduler(context)
        val manager = WorkManager.getInstance(context)
        val category = ReminderCategory.SHORT_DEADLINE
        manager.cancelUniqueWork(WorkManagerReminderScheduler.workName(category)).result.get(10, TimeUnit.SECONDS)
        try {
            val settings = ReminderSettings(shortDeadlineEnabled = true)
            scheduler.scheduleAll(settings, Instant.now())
            val first = manager.getWorkInfosForUniqueWork(WorkManagerReminderScheduler.workName(category)).get(10, TimeUnit.SECONDS).filter { !it.state.isFinished }
            assertEquals(1, first.size)
            scheduler.scheduleAll(settings, Instant.now())
            val second = manager.getWorkInfosForUniqueWork(WorkManagerReminderScheduler.workName(category)).get(10, TimeUnit.SECONDS).filter { !it.state.isFinished }
            assertEquals(first.single().id, second.single().id)
            scheduler.scheduleAll(ReminderSettings(), Instant.now())
            assertTrue(manager.getWorkInfosForUniqueWork(WorkManagerReminderScheduler.workName(category)).get(10, TimeUnit.SECONDS).all { it.state.isFinished })
        } finally {
            manager.cancelAllWorkByTag(WorkManagerReminderScheduler.TAG).result.get(10, TimeUnit.SECONDS)
        }
    }
}
