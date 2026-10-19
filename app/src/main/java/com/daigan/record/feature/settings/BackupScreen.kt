package com.daigan.record.feature.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daigan.record.backup.BackupExportSummary
import com.daigan.record.backup.BackupFormat
import com.daigan.record.backup.RestorePreview
import com.daigan.record.backup.RestoreResult
import com.daigan.record.ui.components.EmptyStateCard
import com.daigan.record.ui.components.LabeledValue
import com.daigan.record.ui.components.PageHeader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun Instant.label(): String =
    atZone(ZoneId.systemDefault()).format(timestampFormatter)

private fun bytesLabel(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
fun BackupScreen(
    onBack: () -> Unit,
    onRestoreFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    BackHandler(enabled = state.anyOperationRunning) { /* finish or recover before leaving */ }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFormat.MIME_TYPE),
    ) { uri ->
        if (uri == null) viewModel.onExportCancelled() else viewModel.export(uri)
    }
    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.inspect(uri)
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
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            PageHeader(title = "备份与恢复", onBack = { if (!state.anyOperationRunning) onBack() })
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ExportCard(
                    state = state,
                    onPasswordChange = viewModel::onExportPasswordChanged,
                    onConfirmChange = viewModel::onExportPasswordConfirmChanged,
                    onStart = {
                        createDocument.launch(
                            viewModel.suggestedFileName(state.exportPassword.isNotEmpty()),
                        )
                    },
                )
                RestoreCard(
                    state = state,
                    onPickFile = { openDocument.launch(arrayOf("*/*")) },
                    onPasswordChange = viewModel::onPasswordChanged,
                    onSubmitPassword = viewModel::submitPassword,
                    onConfirm = viewModel::confirmRestore,
                    onCancel = viewModel::cancelRestore,
                    onDismissFailure = viewModel::clearRestoreFailure,
                    onFinished = onRestoreFinished,
                )
            }
        }
    }
}

@Composable
private fun ExportCard(
    state: BackupUiState,
    onPasswordChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onStart: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "导出完整备份", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "注意：留空密码会导出未加密的备份，其中包含账号密码，请只保存在自己信任的位置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedTextField(
                value = state.exportPassword,
                onValueChange = onPasswordChange,
                label = { Text("备份密码（可留空）") },
                singleLine = true,
                enabled = !state.anyOperationRunning,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("backup-password"),
            )
            if (state.exportPassword.isNotEmpty()) {
                OutlinedTextField(
                    value = state.exportPasswordConfirm,
                    onValueChange = onConfirmChange,
                    label = { Text("再次输入密码") },
                    singleLine = true,
                    enabled = !state.anyOperationRunning,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("backup-password-confirm"),
                )
            }
            state.exportError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.exportRunning) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                    Text("正在整理资料并写入文件…", style = MaterialTheme.typography.bodyMedium)
                }
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Button(
                onClick = onStart,
                enabled = !state.anyOperationRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("backup-export-button"),
            ) {
                Text(if (state.exportPassword.isEmpty()) "选择位置并导出（不加密）" else "选择位置并导出（加密）")
            }
            state.exportResult?.let { result ->
                HorizontalDivider()
                ExportResult(result)
            }
        }
    }
}

@Composable
private fun ExportResult(result: BackupExportSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "最近一次导出成功", style = MaterialTheme.typography.titleSmall)
        LabeledValue("保存为", result.fileName)
        LabeledValue("导出时间", result.exportedAt.label())
        LabeledValue("记录数", "${result.recordCount} 条")
        LabeledValue("截图", "${result.attachmentCount} 张（${bytesLabel(result.attachmentBytes)}）")
        LabeledValue("文件大小", bytesLabel(result.archiveBytes))
        LabeledValue("加密", if (result.encrypted) "已加密（需要密码才能恢复）" else "未加密（包含账号密码）")
    }
}

@Composable
private fun RestoreCard(
    state: BackupUiState,
    onPickFile: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmitPassword: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDismissFailure: () -> Unit,
    onFinished: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "从备份恢复", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "恢复会整体替换当前全部资料，不会与现有记录合并。选择文件后会先校验并显示摘要，" +
                    "只有再次确认后才会替换。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onPickFile,
                enabled = !state.anyOperationRunning && state.restoreStage !is RestoreStage.Success,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("backup-restore-button"),
            ) { Text("选择备份文件") }

            when (val stage = state.restoreStage) {
                RestoreStage.Idle -> Unit

                RestoreStage.Inspecting -> RestoreProgress("正在检查备份文件…")

                RestoreStage.NeedsPassword -> {
                    Text(
                        text = "这个备份已加密，请输入导出时设置的密码。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = state.passwordInput,
                        onValueChange = onPasswordChange,
                        label = { Text("备份密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("restore-password"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onSubmitPassword,
                            modifier = Modifier.testTag("restore-password-submit"),
                        ) { Text("校验备份") }
                        OutlinedButton(onClick = onCancel) { Text("取消") }
                    }
                }

                RestoreStage.Preparing -> RestoreProgress("正在解密并校验备份内容…")

                is RestoreStage.Ready -> RestoreSummary(
                    preview = stage.preview,
                    onConfirm = onConfirm,
                    onCancel = onCancel,
                )

                RestoreStage.Applying -> RestoreProgress("正在替换资料，请保持应用打开…")

                is RestoreStage.Success -> {
                    RestoreSuccess(stage.result)
                    Button(
                        onClick = onFinished,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("restore-finished"),
                    ) { Text("完成") }
                }

                is RestoreStage.Failed -> {
                    EmptyStateCard(title = "恢复未完成", description = stage.message)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onDismissFailure) { Text("知道了") }
                    }
                }
            }
            state.restoreError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RestoreProgress(text: String) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

@Composable
private fun RestoreSummary(
    preview: RestorePreview,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.testTag("restore-preview"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HorizontalDivider()
        Text(text = "备份摘要", style = MaterialTheme.typography.titleSmall)
        LabeledValue("导出时间", preview.exportedAt.label())
        LabeledValue(
            "资料内容",
            "${preview.owners} 位老板 · ${preview.accounts} 个游戏账号 · ${preview.orders} 笔订单",
        )
        LabeledValue(
            "记录",
            "${preview.hostingPeriods} 个托管期次 · ${preview.receipts} 笔实收 · " +
                "${preview.tasks} 项任务 · ${preview.occurrences} 条打卡",
        )
        LabeledValue(
            "截图",
            "${preview.attachments} 张（${bytesLabel(preview.attachmentBytes)}），校验通过",
        )
        LabeledValue(
            "提醒设置",
            if (preview.remindersEnabled == 0) "未开启提醒" else "${preview.remindersEnabled} 类提醒已开启",
        )
        if (preview.empty) {
            Text(
                text = "这个备份没有业务记录，恢复后会得到一个空白的数据集。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = "确认后，当前全部老板、账号、订单、收款、打卡、模板和截图都会被这个备份替换。" +
                "替换前会自动保留一份可回滚的完整快照。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.testTag("restore-confirm"),
            ) { Text("确认替换全部资料") }
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.testTag("restore-cancel"),
            ) { Text("取消") }
        }
    }
}

@Composable
private fun RestoreSuccess(result: RestoreResult) {
    Column(
        modifier = Modifier.testTag("restore-success"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HorizontalDivider()
        Text(text = "恢复完成", style = MaterialTheme.typography.titleSmall)
        LabeledValue("备份导出时间", result.exportedAt.label())
        LabeledValue("老板", "${result.owners} 位")
        LabeledValue("游戏账号", "${result.accounts} 个")
        LabeledValue("订单", "${result.orders} 笔")
        LabeledValue("实收记录", "${result.receipts} 笔")
        LabeledValue("截图", "${result.attachments} 张")
        LabeledValue("总记录数", "${result.recordCount} 条")
    }
}
