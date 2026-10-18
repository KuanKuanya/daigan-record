package com.daigan.record.feature.templates

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.TemplateCategoryDraft
import com.daigan.record.data.model.TemplateDraft
import com.daigan.record.data.model.TemplateTaskDraft
import com.daigan.record.ui.components.EmptyStateCard
import com.daigan.record.ui.components.PageHeader
import java.time.DayOfWeek
import java.time.LocalTime

@Composable
fun TemplateEditorScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TemplateEditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.completed) {
        if (uiState.completed) onDone()
    }

    val requestBack: () -> Unit = {
        if (uiState.dirty && !uiState.saving) confirmDiscard = true else onDone()
    }

    // The system back gesture/button must not silently drop unsaved edits either.
    androidx.activity.compose.BackHandler(enabled = uiState.dirty && !uiState.saving) {
        confirmDiscard = true
    }

    TemplateEditorContent(
        uiState = uiState,
        onBack = requestBack,
        onDraftChange = viewModel::updateDraft,
        onAddCategory = viewModel::addCategory,
        onRemoveCategory = viewModel::removeCategory,
        onUpdateCategory = viewModel::updateCategory,
        onAddTask = viewModel::addTask,
        onRemoveTask = viewModel::removeTask,
        onUpdateTask = viewModel::updateTask,
        onSave = viewModel::save,
        onCancel = requestBack,
        modifier = modifier,
    )

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃这次修改？") },
            text = { Text("未保存的分类、任务与默认值会丢失；已保存的模板不会改变。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        onDone()
                    },
                    modifier = Modifier.testTag("template-discard-confirm"),
                ) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TemplateEditorContent(
    uiState: TemplateEditorUiState,
    onBack: () -> Unit,
    onDraftChange: ((TemplateDraft) -> TemplateDraft) -> Unit,
    onAddCategory: () -> Unit,
    onRemoveCategory: (Int) -> Unit,
    onUpdateCategory: (Int, (TemplateCategoryDraft) -> TemplateCategoryDraft) -> Unit,
    onAddTask: (Int) -> Unit,
    onRemoveTask: (Int, Int) -> Unit,
    onUpdateTask: (Int, Int, (TemplateTaskDraft) -> TemplateTaskDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = uiState.draft
    val errors = uiState.errors
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("template-editor"),
    ) {
        PageHeader(
            title = if (uiState.isNew) "新建游戏模板" else "编辑游戏模板",
            subtitle = uiState.origin?.let { originLabel(it) + " · 设置 ／ 游戏模板" }
                ?: "设置 ／ 游戏模板",
            onBack = onBack,
        )
        when {
            uiState.loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            uiState.loadError != null -> Box(modifier = Modifier.padding(16.dp)) {
                EmptyStateCard(
                    title = "无法编辑模板",
                    description = checkNotNull(uiState.loadError),
                )
            }

            else -> LazyColumn(
                modifier = Modifier.testTag("template-editor-list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("基本信息", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(
                                value = draft.gameName,
                                onValueChange = { value -> onDraftChange { it.copy(gameName = value) } },
                                label = { Text("游戏名称") },
                                singleLine = true,
                                isError = errors.containsKey("gameName"),
                                supportingText = {
                                    Text(errors["gameName"] ?: "用于模板列表与新建订单时的识别")
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("template-game-name"),
                            )
                            OutlinedTextField(
                                value = draft.templateVersion,
                                onValueChange = { value ->
                                    onDraftChange { it.copy(templateVersion = value) }
                                },
                                label = { Text("模板版本") },
                                singleLine = true,
                                isError = errors.containsKey("templateVersion"),
                                supportingText = {
                                    Text(errors["templateVersion"] ?: "例如 2026.10，由你自行维护")
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = draft.gameVersionNotes,
                                onValueChange = { value ->
                                    onDraftChange { it.copy(gameVersionNotes = value) }
                                },
                                label = { Text("游戏版本备注（选填）") },
                                minLines = 2,
                                isError = errors.containsKey("gameVersionNotes"),
                                supportingText = {
                                    Text(errors["gameVersionNotes"] ?: "记录适用版本或未核实的说明")
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("刷新规则", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(
                                value = draft.zoneText,
                                onValueChange = { value -> onDraftChange { it.copy(zoneText = value) } },
                                label = { Text("游戏时区") },
                                singleLine = true,
                                isError = errors.containsKey("zone"),
                                supportingText = {
                                    Text(errors["zone"] ?: "IANA 时区，例如 Asia/Shanghai")
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            ) {
                                listOf("Asia/Shanghai", "Asia/Tokyo", "UTC").forEach { zone ->
                                    AssistChip(
                                        onClick = { onDraftChange { it.copy(zoneText = zone) } },
                                        label = { Text(zone) },
                                    )
                                }
                            }
                            TemplateTimeField(
                                label = "每日刷新时间",
                                time = draft.dailyRefresh,
                                onTime = { time -> onDraftChange { it.copy(dailyRefresh = time) } },
                            )
                            Text(
                                text = "每日任务按该时间划分游戏日，不使用手机零点。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text("每周刷新日", style = MaterialTheme.typography.labelLarge)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            ) {
                                DayOfWeek.entries.forEach { day ->
                                    FilterChip(
                                        selected = draft.weeklyRefreshDay == day,
                                        onClick = {
                                            onDraftChange { it.copy(weeklyRefreshDay = day) }
                                        },
                                        label = { Text(weekdayLabel(day)) },
                                    )
                                }
                            }
                            TemplateTimeField(
                                label = "每周刷新时间",
                                time = draft.weeklyRefreshTime,
                                onTime = { time -> onDraftChange { it.copy(weeklyRefreshTime = time) } },
                            )
                            Text(
                                text = "每周任务在一个刷新周期内只生成一次。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "任务分类与选项",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onAddCategory) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text("添加分类", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    errors["categories"]?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    errors["tasks"]?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        text = "一次性任务只能用于短期单；托管订单只会使用每日、每周、指定星期或" +
                            "每隔若干天的任务。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                draft.categories.forEachIndexed { categoryIndex, category ->
                    item(key = "category-$categoryIndex") {
                        CategoryEditor(
                            index = categoryIndex,
                            category = category,
                            errors = errors,
                            canRemove = draft.categories.size > 1,
                            onUpdate = { transform -> onUpdateCategory(categoryIndex, transform) },
                            onRemove = { onRemoveCategory(categoryIndex) },
                            onAddTask = { onAddTask(categoryIndex) },
                            onRemoveTask = { taskIndex -> onRemoveTask(categoryIndex, taskIndex) },
                            onUpdateTask = { taskIndex, transform ->
                                onUpdateTask(categoryIndex, taskIndex, transform)
                            },
                        )
                    }
                }

                if (uiState.errorMessages.isNotEmpty() || uiState.saveError != null) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                (uiState.errorMessages + listOfNotNull(uiState.saveError)).forEach {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Button(
                        onClick = onSave,
                        enabled = !uiState.saving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("template-save"),
                    ) {
                        Text(if (uiState.saving) "保存中…" else "保存模板")
                    }
                    TextButton(
                        onClick = onCancel,
                        enabled = !uiState.saving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("template-cancel"),
                    ) { Text("取消") }
                    Text(
                        text = "保存只影响以后新建订单的默认值；已有订单保留自己的任务与刷新快照。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryEditor(
    index: Int,
    category: TemplateCategoryDraft,
    errors: Map<String, String>,
    canRemove: Boolean,
    onUpdate: ((TemplateCategoryDraft) -> TemplateCategoryDraft) -> Unit,
    onRemove: () -> Unit,
    onAddTask: () -> Unit,
    onRemoveTask: (Int) -> Unit,
    onUpdateTask: (Int, (TemplateTaskDraft) -> TemplateTaskDraft) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "分类 ${index + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (canRemove) {
                    TextButton(onClick = onRemove) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Text("删除分类", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
            OutlinedTextField(
                value = category.name,
                onValueChange = { value -> onUpdate { it.copy(name = value) } },
                label = { Text("分类名称") },
                singleLine = true,
                isError = errors.containsKey("category.$index"),
                supportingText = {
                    (errors["category.$index"] ?: errors["category.$index.tasks"])?.let {
                        Text(it)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("template-category-$index-name"),
            )
            HorizontalDivider()
            category.tasks.forEachIndexed { taskIndex, task ->
                TaskEditor(
                    categoryIndex = index,
                    taskIndex = taskIndex,
                    task = task,
                    errors = errors,
                    canRemove = category.tasks.size > 1,
                    onUpdate = { transform -> onUpdateTask(taskIndex, transform) },
                    onRemove = { onRemoveTask(taskIndex) },
                )
            }
            TextButton(onClick = onAddTask) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("添加任务", modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun TaskEditor(
    categoryIndex: Int,
    taskIndex: Int,
    task: TemplateTaskDraft,
    errors: Map<String, String>,
    canRemove: Boolean,
    onUpdate: ((TemplateTaskDraft) -> TemplateTaskDraft) -> Unit,
    onRemove: () -> Unit,
) {
    val key = "category.$categoryIndex.task.$taskIndex"
    val taskErrors = errors.filterKeys { it.startsWith(key) }.values.distinct()
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "任务 ${taskIndex + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (canRemove) {
                    TextButton(onClick = onRemove) {
                        Text("删除")
                    }
                }
            }
            OutlinedTextField(
                value = task.title,
                onValueChange = { value -> onUpdate { it.copy(title = value) } },
                label = { Text("任务名称") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("template-task-$categoryIndex-$taskIndex-title"),
            )
            Text("记录方式", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = task.progressType == ProgressType.CHECKBOX,
                    onClick = { onUpdate { it.copy(progressType = ProgressType.CHECKBOX) } },
                    label = { Text("勾选完成") },
                )
                FilterChip(
                    selected = task.progressType == ProgressType.NUMERIC,
                    onClick = { onUpdate { it.copy(progressType = ProgressType.NUMERIC) } },
                    label = { Text("数值进度") },
                )
            }
            if (task.progressType == ProgressType.NUMERIC) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = task.startValue,
                        onValueChange = { value -> onUpdate { it.copy(startValue = value) } },
                        label = { Text("起始值") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = task.targetValue,
                        onValueChange = { value -> onUpdate { it.copy(targetValue = value) } },
                        label = { Text("目标值") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = task.unit,
                    onValueChange = { value -> onUpdate { it.copy(unit = value) } },
                    label = { Text("单位，例如：抽 / %") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text("重复周期", style = MaterialTheme.typography.labelMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                RecurrenceType.entries.forEach { recurrence ->
                    FilterChip(
                        selected = task.recurrence == recurrence,
                        onClick = { onUpdate { it.copy(recurrence = recurrence) } },
                        label = { Text(templateRecurrenceLabel(recurrence)) },
                    )
                }
            }
            when (task.recurrence) {
                RecurrenceType.ONE_TIME -> Text(
                    text = "一次性任务只会进入短期单，不会生成每日托管打卡。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                RecurrenceType.SELECTED_WEEKDAYS -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        DayOfWeek.entries.forEach { day ->
                            FilterChip(
                                selected = day in task.weekdays,
                                onClick = {
                                    onUpdate {
                                        it.copy(
                                            weekdays = if (day in it.weekdays) {
                                                it.weekdays - day
                                            } else {
                                                it.weekdays + day
                                            },
                                        )
                                    }
                                },
                                label = { Text(weekdayShortLabel(day)) },
                            )
                        }
                    }
                }

                RecurrenceType.EVERY_N_DAYS -> OutlinedTextField(
                    value = task.everyNDays,
                    onValueChange = { value -> onUpdate { it.copy(everyNDays = value) } },
                    label = { Text("每隔若干天（以托管期开始日为锚点）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                RecurrenceType.DAILY, RecurrenceType.WEEKLY -> Unit
            }
            taskErrors.forEach { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateTimeField(
    label: String,
    time: LocalTime,
    onTime: (LocalTime) -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = timeText(time),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { TextButton(onClick = { show = true }) { Text("选择") } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (show) {
        val state = rememberTimePickerState(
            initialHour = time.hour,
            initialMinute = time.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(label) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onTime(LocalTime.of(state.hour, state.minute))
                        show = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { show = false }) { Text("取消") }
            },
        )
    }
}
