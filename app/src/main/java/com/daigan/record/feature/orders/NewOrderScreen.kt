package com.daigan.record.feature.orders

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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.ui.components.EmptyStateCard
import com.daigan.record.ui.components.PageHeader
import com.daigan.record.ui.components.StatusTag
import com.daigan.record.ui.components.formatGameDate
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
fun NewOrderScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NewOrderViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.createdOrderId.collect { orderId -> onCreated(orderId) }
    }

    NewOrderScreen(
        uiState = uiState,
        onBack = onBack,
        onSelectType = viewModel::selectType,
        onTitle = viewModel::updateTitle,
        onPrice = viewModel::updatePrice,
        onPaymentMode = viewModel::selectPaymentMode,
        onNotes = viewModel::updateNotes,
        onDeadline = viewModel::setDeadline,
        onStartDate = viewModel::setStartDate,
        onDuration = viewModel::setDurationText,
        onEndDate = viewModel::setEndDate,
        onZone = viewModel::setZoneText,
        onDailyRefresh = viewModel::setDailyRefresh,
        onWeeklyDay = viewModel::setWeeklyRefreshDay,
        onWeeklyTime = viewModel::setWeeklyRefreshTime,
        onAddTask = viewModel::addTask,
        onRemoveTask = viewModel::removeTask,
        onUpdateTask = viewModel::updateTask,
        onSubmit = viewModel::submit,
        snackbar = snackbar,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewOrderScreen(
    uiState: NewOrderUiState,
    onBack: () -> Unit,
    onSelectType: (OrderType) -> Unit,
    onTitle: (String) -> Unit,
    onPrice: (String) -> Unit,
    onPaymentMode: (PaymentMode) -> Unit,
    onNotes: (String) -> Unit,
    onDeadline: (LocalDate?) -> Unit,
    onStartDate: (LocalDate) -> Unit,
    onDuration: (String) -> Unit,
    onEndDate: (LocalDate) -> Unit,
    onZone: (String) -> Unit,
    onDailyRefresh: (LocalTime) -> Unit,
    onWeeklyDay: (DayOfWeek) -> Unit,
    onWeeklyTime: (LocalTime) -> Unit,
    onAddTask: () -> Unit,
    onRemoveTask: (Int) -> Unit,
    onUpdateTask: (Int, (TaskDraft) -> TaskDraft) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PageHeader(
                title = "新增订单",
                subtitle = "老板已确定，选择账号后配置订单",
                onBack = onBack,
            )
            when {
                uiState.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                uiState.loadError != null -> Box(modifier = Modifier.padding(16.dp)) {
                    EmptyStateCard(title = "无法创建订单", description = checkNotNull(uiState.loadError))
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = uiState.owner?.let { "${it.nickname} · ${it.displayNumber}" }
                                        ?: "老板",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = uiState.account?.let {
                                        "${it.gameName} · ${it.server} · ${it.uid}"
                                    } ?: "游戏账号",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }

                    item {
                        SectionTitle("订单类型")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = uiState.type == OrderType.HOSTING,
                                onClick = { onSelectType(OrderType.HOSTING) },
                                label = { Text("长期托管") },
                            )
                            FilterChip(
                                selected = uiState.type == OrderType.SHORT_TASK,
                                onClick = { onSelectType(OrderType.SHORT_TASK) },
                                label = { Text("短期任务") },
                            )
                        }
                    }

                    item {
                        OutlinedTextField(
                            value = uiState.title,
                            onValueChange = onTitle,
                            label = { Text("订单名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (uiState.isShort) {
                        item {
                            DateField(
                                label = "交付期限（选填）",
                                date = uiState.deadlineDate,
                                onDate = onDeadline,
                                onClear = { onDeadline(null) },
                            )
                        }
                    } else {
                        item {
                            SectionTitle("托管期限")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    DateField(
                                        label = "开始日期",
                                        date = uiState.startDate,
                                        onDate = onStartDate,
                                    )
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    OutlinedTextField(
                                        value = uiState.durationText,
                                        onValueChange = onDuration,
                                        label = { Text("托管天数") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            Text(
                                text = "开始日计为第 1 天，可直接调整到期日。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            DateField(
                                label = "到期日期（含当天）",
                                date = uiState.endDate,
                                onDate = onEndDate,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        item {
                            RefreshRulesEditor(
                                zoneText = uiState.zoneText,
                                onZone = onZone,
                                dailyRefresh = uiState.dailyRefresh,
                                onDailyRefresh = onDailyRefresh,
                                weeklyDay = uiState.weeklyRefreshDay,
                                onWeeklyDay = onWeeklyDay,
                                weeklyTime = uiState.weeklyRefreshTime,
                                onWeeklyTime = onWeeklyTime,
                            )
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SectionTitle(
                                if (uiState.isShort) "任务与进度" else "托管任务",
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onAddTask) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                Text("添加任务", modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }

                    uiState.tasks.forEachIndexed { index, task ->
                        item(key = "task-$index") {
                            TaskEditor(
                                index = index,
                                task = task,
                                hosting = !uiState.isShort,
                                onChange = { transform -> onUpdateTask(index, transform) },
                                onRemove = { onRemoveTask(index) },
                                canRemove = uiState.tasks.size > 1,
                            )
                        }
                    }

                    item {
                        SectionTitle(if (uiState.isShort) "整单价格与收款" else "本期价格与收款")
                        OutlinedTextField(
                            value = uiState.priceYuan,
                            onValueChange = onPrice,
                            label = {
                                Text(if (uiState.isShort) "约定总价（元）" else "本期约定价格（元）")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            FilterChip(
                                selected = uiState.paymentMode == PaymentMode.ONE_TIME,
                                onClick = { onPaymentMode(PaymentMode.ONE_TIME) },
                                label = { Text("一次结清") },
                            )
                            FilterChip(
                                selected = uiState.paymentMode == PaymentMode.INSTALLMENTS,
                                onClick = { onPaymentMode(PaymentMode.INSTALLMENTS) },
                                label = { Text("分批收款") },
                            )
                        }
                        PendingPaymentNote()
                    }

                    item {
                        OutlinedTextField(
                            value = uiState.notes,
                            onValueChange = onNotes,
                            label = { Text("订单备注（选填）") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
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
                            onClick = onSubmit,
                            enabled = !uiState.saving,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (uiState.saving) "正在创建…" else "创建订单")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
internal fun PendingPaymentNote() {
    Text(
        text = "收款记录将在第三阶段提供：本版本只保存约定价格与收款方式，" +
            "不会创建首笔实收、余额或任何虚构流水。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(
    label: String,
    date: LocalDate?,
    onDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
) {
    var show by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = date?.let(::formatGameDate).orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                Row {
                    if (date != null && onClear != null) {
                        TextButton(onClick = onClear) { Text("清除") }
                    }
                    TextButton(onClick = { show = true }) { Text("选择") }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (show) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now()).toUtcMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { onDate(it.toUtcLocalDate()) }
                        show = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { show = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(
    label: String,
    time: LocalTime,
    onTime: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    var show by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = "%02d:%02d".format(time.hour, time.minute),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                TextButton(onClick = { show = true }) { Text("选择") }
            },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshRulesEditor(
    zoneText: String,
    onZone: (String) -> Unit,
    dailyRefresh: LocalTime,
    onDailyRefresh: (LocalTime) -> Unit,
    weeklyDay: DayOfWeek,
    onWeeklyDay: (DayOfWeek) -> Unit,
    weeklyTime: LocalTime,
    onWeeklyTime: (LocalTime) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("刷新规则（用户自填 · 待第四阶段核对官方模板）")
            OutlinedTextField(
                value = zoneText,
                onValueChange = onZone,
                label = { Text("游戏时区") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                AssistChip(
                    onClick = { onZone("Asia/Shanghai") },
                    label = { Text("Asia/Shanghai") },
                )
                AssistChip(
                    onClick = { onZone("Asia/Tokyo") },
                    label = { Text("Asia/Tokyo") },
                )
            }
            TimeField(
                label = "每日刷新时间",
                time = dailyRefresh,
                onTime = onDailyRefresh,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "每日任务按该时间划分游戏日，不使用手机零点。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "每周刷新星期",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(
                        selected = weeklyDay == day,
                        onClick = { onWeeklyDay(day) },
                        label = { Text(dayLabel(day)) },
                    )
                }
            }
            TimeField(
                label = "每周刷新时间",
                time = weeklyTime,
                onTime = onWeeklyTime,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = "每周任务在一个刷新周期内只生成一次；周期不会按每天复制。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TaskEditor(
    index: Int,
    task: TaskDraft,
    hosting: Boolean,
    onChange: ((TaskDraft) -> TaskDraft) -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "任务 ${index + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (canRemove) {
                    TextButton(onClick = onRemove) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除任务")
                        Text("删除")
                    }
                }
            }
            OutlinedTextField(
                value = task.title,
                onValueChange = { value -> onChange { it.copy(title = value) } },
                label = { Text("任务名称，例如：每日签到") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "记录方式",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = task.progressType == ProgressType.CHECKBOX,
                    onClick = { onChange { it.copy(progressType = ProgressType.CHECKBOX) } },
                    label = { Text("勾选完成") },
                )
                FilterChip(
                    selected = task.progressType == ProgressType.NUMERIC,
                    onClick = { onChange { it.copy(progressType = ProgressType.NUMERIC) } },
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
                        onValueChange = { value -> onChange { it.copy(startValue = value) } },
                        label = { Text("起始值") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = task.targetValue,
                        onValueChange = { value -> onChange { it.copy(targetValue = value) } },
                        label = { Text("目标值") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = task.unit,
                    onValueChange = { value -> onChange { it.copy(unit = value) } },
                    label = { Text("单位，例如：抽 / %") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (hosting) {
                Text(
                    text = "重复周期",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    RecurrenceType.entries.forEach { recurrence ->
                        FilterChip(
                            selected = task.recurrence == recurrence,
                            onClick = { onChange { it.copy(recurrence = recurrence) } },
                            label = { Text(recurrenceLabel(recurrence)) },
                        )
                    }
                }
                when (task.recurrence) {
                    RecurrenceType.SELECTED_WEEKDAYS -> {
                        Text(
                            text = "执行星期",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            DayOfWeek.entries.forEach { day ->
                                FilterChip(
                                    selected = day in task.weekdays,
                                    onClick = {
                                        onChange {
                                            it.copy(
                                                weekdays = if (day in it.weekdays) {
                                                    it.weekdays - day
                                                } else {
                                                    it.weekdays + day
                                                },
                                            )
                                        }
                                    },
                                    label = { Text(dayLabel(day)) },
                                )
                            }
                        }
                    }

                    RecurrenceType.EVERY_N_DAYS -> {
                        OutlinedTextField(
                            value = task.everyNDays,
                            onValueChange = { value -> onChange { it.copy(everyNDays = value) } },
                            label = { Text("每隔若干天（从托管开始日算起）") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                        )
                    }

                    RecurrenceType.WEEKLY -> Text(
                        text = "使用上方每周刷新星期与时间；每个游戏周只出现一次。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    RecurrenceType.DAILY -> Unit
                }
            }
        }
    }
}

private fun recurrenceLabel(type: RecurrenceType): String = when (type) {
    RecurrenceType.DAILY -> "每日"
    RecurrenceType.WEEKLY -> "每周"
    RecurrenceType.SELECTED_WEEKDAYS -> "指定星期"
    RecurrenceType.EVERY_N_DAYS -> "每隔若干天"
}

private fun dayLabel(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "一"
    DayOfWeek.TUESDAY -> "二"
    DayOfWeek.WEDNESDAY -> "三"
    DayOfWeek.THURSDAY -> "四"
    DayOfWeek.FRIDAY -> "五"
    DayOfWeek.SATURDAY -> "六"
    DayOfWeek.SUNDAY -> "日"
}

private fun LocalDate.toUtcMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
