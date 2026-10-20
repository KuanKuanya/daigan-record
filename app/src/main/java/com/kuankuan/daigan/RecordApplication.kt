/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kuankuan.daigan

import android.app.Application
import com.kuankuan.daigan.notifications.RecordWorkerFactory
import androidx.work.Configuration
import com.kuankuan.daigan.backup.BackupDatabaseAccess
import com.kuankuan.daigan.backup.BackupRestoreService
import com.kuankuan.daigan.data.repository.ApplicationReadiness
import com.kuankuan.daigan.data.repository.AttachmentRepository
import com.kuankuan.daigan.notifications.ReminderCoordinator
import com.kuankuan.daigan.notifications.ReminderRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

@HiltAndroidApp
class RecordApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: RecordWorkerFactory

    @Inject
    lateinit var attachmentRepository: AttachmentRepository

    @Inject
    lateinit var restoreService: BackupRestoreService

    @Inject
    lateinit var databaseAccess: BackupDatabaseAccess

    @Inject
    lateinit var reminderCoordinator: ReminderCoordinator

    @Inject
    lateinit var reminderRepository: ReminderRepository

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private var startupJob: Job? = null
    private var businessObserver: Job? = null

    override fun onCreate() {
        ApplicationReadiness.begin()
        super.onCreate()
        retryStartup()
    }

    @OptIn(FlowPreview::class)
    fun retryStartup() {
        if (startupJob?.isActive == true) return
        businessObserver?.cancel()
        ApplicationReadiness.begin()
        startupJob = startupScope.launch {
            try {
                restoreService.recoverIfNeeded()
                databaseAccess.ensureDatasetIdentity()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Do not show business screens or let workers read a partial dataset.
                ApplicationReadiness.fail()
                return@launch
            }
            ApplicationReadiness.ready()
            runCatching { attachmentRepository.cleanupOrphans() }
            runCatching { reminderCoordinator.refreshSchedule() }
            businessObserver = startupScope.launch {
                reminderRepository.observeBusinessChanges()
                    .debounce(BUSINESS_CHANGE_DEBOUNCE_MILLIS)
                    .collect { runCatching { reminderCoordinator.refreshSchedule() } }
            }
        }
    }

    fun onForeground() {
        startupScope.launch {
            runCatching {
                ApplicationReadiness.awaitReady()
                reminderCoordinator.refreshSchedule()
            }
        }
    }

    private companion object {
        const val BUSINESS_CHANGE_DEBOUNCE_MILLIS = 1_500L
    }
}
