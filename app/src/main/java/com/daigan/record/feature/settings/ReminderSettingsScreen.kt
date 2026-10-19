package com.daigan.record.feature.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daigan.record.notifications.ReminderCategory
import com.daigan.record.notifications.ReminderTiming
import com.daigan.record.ui.components.PageHeader

private data class CategoryCopy(
    val category: ReminderCategory,
)

private val categoryCopies = listOf(
    CategoryCopy(ReminderCategory.SHORT_DEADLINE),
    CategoryCopy(ReminderCategory.HOSTING_EXPIRY),
    CategoryCopy(ReminderCategory.UNFINISHED_TASKS),
)

@Composable
fun ReminderSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReminderSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var editingCategoryKey by rememberSaveable { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // The switch state is kept; the banner explains that the system still blocks
        // notifications so the user can fix it without a second unsolicited prompt.
        viewModel.refreshPermission()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermission()
    }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbar.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PageHeader(title = "提醒设置", onBack = onBack)
            if (state.loading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { CircularProgressIndicator() }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!state.notificationsAllowed) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "系统通知未开启",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = "允许系统通知后即可接收提醒。",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                OutlinedButton(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(viewModel.notificationSettingsIntent())
                                        }
                                    },
                                    modifier = Modifier.testTag("reminder-open-system-settings"),
                                ) { Text("前往系统设置") }
                            }
                        }
                    }

                    categoryCopies.forEach { copy ->
                        ReminderCategoryCard(
                            copy = copy,
                            enabled = state.settings.enabled(copy.category),
                            time = ReminderTiming.formatTime(state.settings.minutes(copy.category)),
                            saving = state.saving,
                            onToggle = { enabled ->
                                viewModel.setEnabled(copy.category, enabled)
                                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    !state.notificationsAllowed
                                ) {
                                    runCatching {
                                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                            },
                            onEditTime = { editingCategoryKey = copy.category.key },
                        )
                    }
                }
            }
        }
    }

    val editingCategory = categoryCopies.firstOrNull { it.category.key == editingCategoryKey }?.category
    if (editingCategory != null) {
        ReminderTimeInputDialog(
            category = editingCategory,
            initialMinutes = state.settings.minutes(editingCategory),
            onDismiss = { editingCategoryKey = null },
            onSave = { category, minutes ->
                viewModel.setTime(category, minutes)
                editingCategoryKey = null
            },
        )
    }
}

@Composable
private fun ReminderCategoryCard(
    copy: CategoryCopy,
    enabled: Boolean,
    time: String,
    saving: Boolean,
    onToggle: (Boolean) -> Unit,
    onEditTime: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = copy.category.label, style = MaterialTheme.typography.titleMedium)
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    enabled = !saving,
                    modifier = Modifier.testTag("reminder-switch-${copy.category.key}"),
                )
            }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "提醒时间 $time",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onEditTime,
                    enabled = enabled && !saving,
                    modifier = Modifier.testTag("reminder-time-${copy.category.key}"),
                ) { Text("修改时间") }
            }
        }
    }
}
