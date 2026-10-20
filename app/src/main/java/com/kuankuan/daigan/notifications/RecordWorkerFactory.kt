package com.kuankuan.daigan.notifications

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import javax.inject.Inject
import javax.inject.Singleton

/** Uses the official factory boundary without an additional worker annotation processor. */
@Singleton
class RecordWorkerFactory @Inject constructor(
    private val coordinator: ReminderCoordinator,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = if (workerClassName == ReminderWorker::class.java.name) {
        ReminderWorker(appContext, workerParameters, coordinator)
    } else null
}
