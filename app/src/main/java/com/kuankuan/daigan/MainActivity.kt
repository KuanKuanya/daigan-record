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

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.kuankuan.daigan.data.repository.DatasetGate
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kuankuan.daigan.backup.BackupDatabaseAccess
import com.kuankuan.daigan.data.local.dao.AppSettingsDao
import com.kuankuan.daigan.data.repository.ApplicationReadiness
import com.kuankuan.daigan.ui.navigation.DaiganRecordApp
import com.kuankuan.daigan.ui.navigation.DeepLink
import com.kuankuan.daigan.ui.theme.DaiganHandbookTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsDao: AppSettingsDao
    @Inject lateinit var databaseAccess: BackupDatabaseAccess
    private val pendingOrderId = MutableStateFlow<String?>(null)
    private val homeRequest = MutableStateFlow(0L)
    private var currentDatasetId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val discardState = intent.getBooleanExtra(EXTRA_DISCARD_STATE, false)
        intent.removeExtra(EXTRA_DISCARD_STATE)
        super.onCreate(if (discardState) null else savedInstanceState)
        val savedDatasetId = if (discardState) null else savedInstanceState?.getString(KEY_DATASET)
        enableEdgeToEdge()
        pendingOrderId.value = DeepLink.extractOrderId(intent)
        setContent {
            val readiness by ApplicationReadiness.state.collectAsStateWithLifecycle()
            val replacing by DatasetGate.activity.collectAsStateWithLifecycle(initialValue = false)
            val orderId by pendingOrderId.collectAsStateWithLifecycle()
            val openHome by homeRequest.collectAsStateWithLifecycle()
            DaiganHandbookTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                    Box(if (replacing) Modifier.clearAndSetSemantics { } else Modifier) {
                    if (readiness is ApplicationReadiness.State.Ready) {
                        // The production startup already creates this; test applications
                        // and empty upgraded installations initialize it here as well.
                        LaunchedEffect(Unit) { databaseAccess.ensureDatasetIdentity() }
                        val datasetId by settingsDao.observeDatasetInstanceId()
                            .collectAsStateWithLifecycle(initialValue = null)
                        if (datasetId != null && savedDatasetId != null && savedDatasetId != datasetId) {
                            // OS-restored forms/navigation belong to the replaced dataset.
                            // Recreate without restoring that old activity state.
                            LaunchedEffect(datasetId) {
                                intent.putExtra(EXTRA_DISCARD_STATE, true)
                                DeepLink.consume(intent)
                                recreate()
                            }
                        } else if (datasetId != null) {
                            SideEffect { currentDatasetId = datasetId }
                            key(datasetId) {
                                DaiganRecordApp(
                                    initialOrderId = orderId,
                                    homeRequest = openHome,
                                    onInitialOrderHandled = {
                                        pendingOrderId.value = null
                                        DeepLink.consume(intent)
                                    },
                                )
                            }
                        } else StartupProgress()
                    } else Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        when (val status = readiness) {
                            is ApplicationReadiness.State.Failed -> {
                                Text(status.message)
                                Button(onClick = { (application as? RecordApplication)?.retryStartup() }) {
                                    Text("重试恢复")
                                }
                            }
                            else -> { CircularProgressIndicator(); Text("正在检查并恢复资料…") }
                        }
                    }
                    }
                    if (replacing) Surface(
                        modifier = Modifier.fillMaxSize().clickable { },
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        BackHandler { }
                        Column(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) { CircularProgressIndicator(); Text("正在处理资料，请稍候…") }
                    }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        currentDatasetId?.let { outState.putString(KEY_DATASET, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        (application as? RecordApplication)?.onForeground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOrderId.value = DeepLink.extractOrderId(intent)
        if (intent.action == Intent.ACTION_MAIN) homeRequest.value++
    }

    private companion object {
        const val KEY_DATASET = "activity.dataset-instance"
        const val EXTRA_DISCARD_STATE = "activity.discard-old-dataset-state"
    }
}

@androidx.compose.runtime.Composable
private fun StartupProgress() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { CircularProgressIndicator(); Text("正在打开资料…") }
}
