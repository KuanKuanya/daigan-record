package com.daigan.record.feature.templates

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daigan.record.data.model.GameTemplateSummary
import com.daigan.record.ui.components.EmptyStateCard
import com.daigan.record.ui.components.PageHeader
import com.daigan.record.ui.components.StatusTag

@Composable
fun TemplateLibraryScreen(
    onBack: () -> Unit,
    onCreateTemplate: () -> Unit,
    onEditTemplate: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TemplateLibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // The pending export target is remembered across a configuration change so the
    // provider result still writes the template the user tapped.
    var exportTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportFileName by rememberSaveable { mutableStateOf("daigan-template.json") }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) {
            // A canceled selection is not an error and must not change the library.
            viewModel.cancelImport()
        } else {
            viewModel.onImportPicked(uri, context.displayName(uri))
        }
    }
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        val templateId = exportTemplateId
        exportTemplateId = null
        if (uri != null && templateId != null) {
            viewModel.exportTo(uri, templateId)
        }
    }

    val message = uiState.message
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("template-library"),
        ) {
            PageHeader(
                title = "游戏模板",
                subtitle = "设置 ／ 游戏模板",
                onBack = onBack,
            )
            if (uiState.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onCreateTemplate,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("template-library-new"),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("新建", modifier = Modifier.padding(start = 4.dp))
                    }
                    OutlinedButton(
                        // SAF shows only documents the user picks; no storage permission
                        // is requested. Every candidate is validated by the codec, so a
                        // broad MIME filter cannot import business data.
                        onClick = { openDocument.launch(arrayOf("*/*")) },
                        enabled = !uiState.busy,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("template-library-import"),
                    ) {
                        Icon(Icons.Filled.FileUpload, contentDescription = null)
                        Text("导入", modifier = Modifier.padding(start = 4.dp))
                    }
                }
                Text(
                    text = "模板只作为新建订单时的默认值；修改、替换或删除模板不会改动已有订单、" +
                        "价格、打卡或收款记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                uiState.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                uiState.templates.isEmpty() -> Box(modifier = Modifier.padding(16.dp)) {
                    EmptyStateCard(
                        title = "模板库为空",
                        description = "可以新建模板，或从 JSON 文件导入；当前没有可用的内置模板。",
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.testTag("template-library-list"),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(uiState.templates, key = { it.id }) { template ->
                        TemplateCard(
                            template = template,
                            onEdit = { onEditTemplate(template.id) },
                            onExport = {
                                exportTemplateId = template.id
                                exportFileName = templateFileName(
                                    template.gameName,
                                    template.templateVersion,
                                )
                                createDocument.launch(exportFileName)
                            },
                            onDelete = { viewModel.requestDelete(template) },
                        )
                    }
                    item {
                        Text(
                            text = "导入会先在预览中显示内容与同名冲突，取消不会改动任何模板。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    uiState.importPreview?.let { preview ->
        TemplateImportDialog(
            preview = preview,
            busy = uiState.busy,
            onReplace = viewModel::confirmImportReplace,
            onSaveAs = viewModel::confirmImportSaveAs,
            onCancel = viewModel::cancelImport,
        )
    }

    uiState.deleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text("删除模板？") },
            text = {
                Text(
                    "将删除「${candidate.label}」。已有订单、期次、打卡与收款不会变化，" +
                        "使用该模板的账号只会失去默认模板。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmDelete,
                    modifier = Modifier.testTag("template-delete-confirm"),
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TemplateCard(
    template: GameTemplateSummary,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("template-card-${template.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = template.gameName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusTag(originLabel(template.origin))
            }
            Text(
                text = "模板版本 ${template.templateVersion}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            template.gameVersionNotes?.let { notes ->
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = refreshSummary(template),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = taskCountSummary(template),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("template-edit-${template.id}"),
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Text("编辑", modifier = Modifier.padding(start = 4.dp))
                }
                TextButton(
                    onClick = onExport,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("template-export-${template.id}"),
                ) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null)
                    Text("导出", modifier = Modifier.padding(start = 4.dp))
                }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("template-delete-${template.id}"),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text("删除", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

private fun android.content.Context.displayName(uri: Uri): String? = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}.getOrNull()
