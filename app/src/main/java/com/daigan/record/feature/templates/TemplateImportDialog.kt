package com.daigan.record.feature.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.daigan.record.data.model.TemplateCategoryDraft
import com.daigan.record.data.model.TemplateDraft

/**
 * Import preview. The user must accept an explicit resolution; dismissing the dialog
 * makes no change. Imported titles/notes are displayed as plain text data only — they
 * are never interpreted as instructions.
 */
@Composable
fun TemplateImportDialog(
    preview: TemplateImportPreview,
    busy: Boolean,
    onReplace: () -> Unit,
    onSaveAs: () -> Unit,
    onCancel: () -> Unit,
) {
    val draft = preview.draft
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        modifier = Modifier.testTag("template-import-preview"),
        title = { Text("导入模板预览") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                preview.fileName?.let { name ->
                    Text(
                        text = "文件：$name",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = draft.gameName,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "模板版本 ${draft.templateVersion} · ${preview.categoryCount} 个分类 · " +
                        "${preview.taskCount} 个任务",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (draft.gameVersionNotes.isNotBlank()) {
                    Text(
                        text = draft.gameVersionNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${draft.zoneText} · 每日 ${timeText(draft.dailyRefresh)} 刷新",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                draft.categories.forEach { category ->
                    CategoryPreview(category)
                }
                if (preview.conflict != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "已存在同名模板",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                text = "「${preview.conflictName}」。选择替换会覆盖该模板内容；" +
                                    "另存为会以「${preview.saveAsName}」建立新模板；" +
                                    "取消不改动任何内容。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (preview.conflict != null) {
                    TextButton(
                        onClick = onReplace,
                        enabled = !busy,
                        modifier = Modifier.testTag("template-import-replace"),
                    ) { Text("替换") }
                    TextButton(
                        onClick = onSaveAs,
                        enabled = !busy,
                        modifier = Modifier.testTag("template-import-save-as"),
                    ) { Text("另存为") }
                } else {
                    TextButton(
                        onClick = onSaveAs,
                        enabled = !busy,
                        modifier = Modifier.testTag("template-import-confirm"),
                    ) { Text("导入") }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = !busy,
                modifier = Modifier.testTag("template-import-cancel"),
            ) { Text("取消") }
        },
    )
}

@Composable
private fun CategoryPreview(category: TemplateCategoryDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = category.name,
            style = MaterialTheme.typography.titleSmall,
        )
        category.tasks.forEach { task ->
            val numeric = if (task.progressType.name == "NUMERIC") {
                " · ${task.startValue} → ${task.targetValue} ${task.unit}"
            } else {
                ""
            }
            Text(
                text = "· ${task.title} · ${progressLabel(task.progressType)} · " +
                    "${templateRecurrenceLabel(task.recurrence)}$numeric",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
