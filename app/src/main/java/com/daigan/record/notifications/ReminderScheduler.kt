package com.daigan.record.notifications

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import java.util.concurrent.ExecutionException

/**
 * Persistent scheduling boundary. The production implementation uses WorkManager; tests
 * use a recording fake, so no test waits for a real deadline.
 *
 * Scheduling identities are stable strings derived from the category key. Re-scheduling
 * keeps pending work; a completed check appends the next day without cancelling itself.
 */
interface ReminderScheduler {

    suspend fun scheduleAll(settings: ReminderSettings, now: Instant)

    suspend fun scheduleCategory(category: ReminderCategory, minutes: Int, now: Instant)

    suspend fun cancel(category: ReminderCategory)

    suspend fun cancelAll()
}

@Singleton
class WorkManagerReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReminderScheduler {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override suspend fun scheduleAll(settings: ReminderSettings, now: Instant) {
        ReminderCategory.entries.forEach { category ->
            if (settings.enabled(category)) {
                enqueue(category, settings.minutes(category), now, ExistingWorkPolicy.KEEP)
            } else {
                cancel(category)
            }
        }
    }

    override suspend fun scheduleCategory(category: ReminderCategory, minutes: Int, now: Instant) {
        enqueue(category, minutes, now, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    private suspend fun enqueue(
        category: ReminderCategory,
        minutes: Int,
        now: Instant,
        policy: ExistingWorkPolicy,
    ) {
        val zone = ZoneId.systemDefault()
        val delay = ReminderTiming.delayMillis(now, minutes, zone)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(
                Data.Builder()
                    .putString(ReminderWorker.KEY_CATEGORY, category.key)
                    .build(),
            )
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(workName(category), policy, request).awaitCommit()
    }

    override suspend fun cancel(category: ReminderCategory) {
        workManager.cancelUniqueWork(workName(category)).awaitCommit()
    }

    override suspend fun cancelAll() {
        ReminderCategory.entries.forEach { cancel(it) }
    }

    /** Do not acknowledge a restore or worker completion before WorkManager commits. */
    private suspend fun Operation.awaitCommit(): Unit = suspendCancellableCoroutine { continuation ->
        result.addListener({
            try {
                result.get()
                continuation.resume(Unit)
            } catch (failure: ExecutionException) {
                continuation.resumeWithException(failure.cause ?: failure)
            } catch (failure: Exception) {
                continuation.resumeWithException(failure)
            }
        }, Executor { it.run() })
    }

    companion object {
        const val TAG = "daigan-reminder"

        /** Stable, unique per category. Not derived from a platform-generated id. */
        fun workName(category: ReminderCategory): String = "daigan-reminder-${category.key}"
    }
}
