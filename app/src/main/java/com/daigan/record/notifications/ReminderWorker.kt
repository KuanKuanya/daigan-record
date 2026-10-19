package com.daigan.record.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * One persistent, deferred reminder check.
 *
 * The worker does not run a service and does not keep the app alive. It reads the
 * current settings and current business rows, posts at most one notification per
 * category and day, and schedules the next day's check. WorkManager may delay the run;
 * the product never promises an exact minute.
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
    private val coordinator: ReminderCoordinator,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val category = inputData.getString(KEY_CATEGORY)
            ?: return Result.success()
        return try {
            com.daigan.record.data.repository.ApplicationReadiness.awaitReady()
            coordinator.deliver(category)
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Preserve the chain across transient DB or scheduler failures.
            Result.retry()
        }
    }

    companion object {
        const val KEY_CATEGORY = "reminder-category"
    }
}
