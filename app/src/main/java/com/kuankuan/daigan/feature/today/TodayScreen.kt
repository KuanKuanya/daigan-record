package com.kuankuan.daigan.feature.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.kuankuan.daigan.core.time.HostingStatus
import com.kuankuan.daigan.data.model.Money
import com.kuankuan.daigan.data.model.OrderTask
import com.kuankuan.daigan.data.model.ProgressType
import com.kuankuan.daigan.data.model.ShortWorkOrder
import com.kuankuan.daigan.data.model.TodayHostingOrder
import com.kuankuan.daigan.data.model.UndoResult
import com.kuankuan.daigan.ui.components.CompletionRow
import com.kuankuan.daigan.ui.components.EmptyStateCard
import com.kuankuan.daigan.ui.components.PageHeader
import com.kuankuan.daigan.ui.components.StatusTag
import com.kuankuan.daigan.ui.components.formatGameDate
import com.kuankuan.daigan.ui.components.formatShortDate
import java.math.BigDecimal
import java.time.temporal.ChronoUnit

@Composable
fun TodayScreen(
    onOpenSettings: () -> Unit,
    onOpenOrder: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refresh()
        }
    }

    TodayScreen(
        uiState = uiState,
        onSelectTab = viewModel::selectTab,
        onOpenSettings = onOpenSettings,
        onOpenOrder = onOpenOrder,
        onCompleteAll = viewModel::completeAllHosting,
        onCompleteOrder = viewModel::completeHostingOrder,
        onToggleHostingTask = viewModel::toggleHostingTask,
        onUpdateHostingProgress = viewModel::updateHostingProgress,
        onToggleShortTask = viewModel::toggleShortTask,
        onUpdateShortProgress = viewModel::updateShortProgress,
        onUndo = viewModel::undoLastBatch,
        onClearUndo = viewModel::clearUndoResult,
        onToggleCompleted = viewModel::toggleCompletedSection,
        snackbar = snackbar,
        modifier = modifier,
    )
}

@Composable
internal fun TodayScreen(
    uiState: TodayUiState,
    onSelectTab: (TodayTab) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOrder: (String) -> Unit,
    onCompleteAll: () -> Unit,
    onCompleteOrder: (String) -> Unit,
    onToggleHostingTask: (String, String, Boolean) -> Unit,
    onUpdateHostingProgress: (String, String, String) -> Unit,
    onToggleShortTask: (String, Boolean) -> Unit,
    onUpdateShortProgress: (String, String) -> Unit,
    onUndo: () -> Unit,
    onClearUndo: () -> Unit,
    onToggleCompleted: () -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PageHeader(
                title = "今日事项",
                subtitle = uiState.dateLabel,
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
            TabRow(selectedTabIndex = uiState.selectedTab.ordinal) {
                TodayTab.entries.forEach { tab ->
                    Tab(
                        selected = uiState.selectedTab == tab,
                        onClick = { onSelectTab(tab) },
                        text = { Text(tab.label) },
                    )
                }
            }

            when {
                uiState.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                uiState.error != null -> Box(modifier = Modifier.padding(16.dp)) {
                    EmptyStateCard(title = "无法读取今日事项", description = checkNotNull(uiState.error))
                }

                uiState.selectedTab == TodayTab.HOSTING -> HostingList(
                    uiState = uiState,
                    onOpenOrder = onOpenOrder,
                    onCompleteAll = onCompleteAll,
                    onCompleteOrder = onCompleteOrder,
                    onToggleTask = onToggleHostingTask,
                    onUpdateProgress = onUpdateHostingProgress,
                    onUndo = onUndo,
                    onToggleCompleted = onToggleCompleted,
                )

                else -> ShortList(
                    uiState = uiState,
                    onOpenOrder = onOpenOrder,
                    onToggleTask = onToggleShortTask,
                    onUpdateProgress = onUpdateShortProgress,
                )
            }
        }
    }

    uiState.undoResult?.let { result ->
        UndoResultDialog(result = result, onDismiss = onClearUndo)
    }
}

@Composable
private fun HostingList(
    uiState: TodayUiState,
    onOpenOrder: (String) -> Unit,
    onCompleteAll: () -> Unit,
    onCompleteOrder: (String) -> Unit,
    onToggleTask: (String, String, Boolean) -> Unit,
    onUpdateProgress: (String, String, String) -> Unit,
    onUndo: () -> Unit,
    onToggleCompleted: () -> Unit,
) {
    val actionable = uiState.hosting.filter {
        it.status == HostingStatus.ACTIVE && it.tasks.any { task -> !task.completed }
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (uiState.hosting.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "还没有托管打卡",
                    description = "暂时没有托管打卡。",
                )
            }
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onCompleteAll,
                        enabled = actionable.isNotEmpty(),
                    ) { Text("所有应做托管单完成") }
                    if (uiState.lastBatchId != null) {
                        TextButton(onClick = onUndo) { Text("撤销") }
                    }
                }
            }


            uiState.activeHosting.forEach { order ->
                item(key = "hosting-${order.order.id}") {
                    HostingOrderCard(
                        order = order,
                        onOpen = { onOpenOrder(order.order.id) },
                        onCompleteOrder = { onCompleteOrder(order.order.id) },
                        onToggleTask = onToggleTask,
                        onUpdateProgress = onUpdateProgress,
                    )
                }
            }

            if (uiState.completedHosting.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onToggleCompleted)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "已完成 · ${uiState.completedHosting.size} 单",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (uiState.completedExpanded) {
                                Icons.Filled.ExpandLess
                            } else {
                                Icons.Filled.ExpandMore
                            },
                            contentDescription = if (uiState.completedExpanded) "收起已完成" else "展开已完成",
                        )
                    }
                }
                if (uiState.completedExpanded) {
                    uiState.completedHosting.forEach { order ->
                        item(key = "completed-${order.order.id}") {
                            CompletedHostingCard(
                                order = order,
                                onOpen = { onOpenOrder(order.order.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HostingOrderCard(
    order: TodayHostingOrder,
    onOpen: () -> Unit,
    onCompleteOrder: () -> Unit,
    onToggleTask: (String, String, Boolean) -> Unit,
    onUpdateProgress: (String, String, String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(order.order.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${order.ownerNickname} · ${order.ownerNumber}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusTag(
                    text = when (order.status) {
                        HostingStatus.EXPIRED -> "已到期 · 待续期"
                        HostingStatus.NOT_STARTED -> "未开始"
                        HostingStatus.ACTIVE -> when {
                            order.tasks.isEmpty() -> "今日无任务"
                            order.allTasksCompleted -> "今日已完成"
                            else -> "待完成"
                        }
                    },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "${order.gameName} · ${order.server}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${formatGameDate(order.period.startDate)} — ${formatGameDate(order.period.endDate)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (order.status) {
                HostingStatus.EXPIRED -> Text(
                    text = "已到期，不再生成新任务；历史记录、截图与期次价格保留。可进入详情续期或补录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )

                HostingStatus.NOT_STARTED -> Text(
                    text = "托管尚未开始，不会提前生成任务。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )

                HostingStatus.ACTIVE -> {
                    order.tasks.forEach { task ->
                        CompletionRow(
                            title = task.task.title,
                            checked = task.completed,
                            onCheckedChange = { completed ->
                                onToggleTask(task.task.id, task.slot.key, completed)
                            },
                            subtitle = buildString {
                                if (task.windowLabel != null) {
                                    append("游戏周 ${task.windowLabel}")
                                }
                                if (task.task.progressType == ProgressType.NUMERIC) {
                                    if (isNotEmpty()) append(" · ")
                                    append(
                                        "进度 ${(task.value ?: task.task.currentValue).toPlainString()}/" +
                                            task.task.targetValue.toPlainString(),
                                    )
                                    task.task.unit?.let { append(" $it") }
                                }
                            },
                            trailing = {
                                if (task.task.progressType == ProgressType.NUMERIC) {
                                    ProgressDialogButton(
                                        initialValue = (task.value ?: task.task.currentValue).toPlainString(),
                                        onValue = { onUpdateProgress(task.task.id, task.slot.key, it) },
                                    )
                                }
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (order.status == HostingStatus.ACTIVE && order.tasks.any { !it.completed }) {
                    TextButton(onClick = onCompleteOrder) { Text("整单完成") }
                }
                Box(modifier = Modifier.weight(1f))
                TextButton(onClick = onOpen) {
                    Text(if (order.status == HostingStatus.EXPIRED) "查看并续期" else "订单详情")
                }
            }
        }
    }
}

@Composable
private fun CompletedHostingCard(order: TodayHostingOrder, onOpen: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(order.order.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "${order.ownerNickname} · ${order.gameName} · 今日任务已全部完成",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            StatusTag(text = "已完成")
        }
    }
}

@Composable
private fun ShortList(
    uiState: TodayUiState,
    onOpenOrder: (String) -> Unit,
    onToggleTask: (String, Boolean) -> Unit,
    onUpdateProgress: (String, String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (uiState.shortOrders.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "还没有短期任务",
                    description = "暂时没有短期任务。",
                )
            }
        } else {
            uiState.shortOrders.forEach { order ->
                item(key = "short-${order.order.id}") {
                    ShortOrderCard(
                        order = order,
                        onOpen = { onOpenOrder(order.order.id) },
                        onToggleTask = onToggleTask,
                        onUpdateProgress = onUpdateProgress,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortOrderCard(
    order: ShortWorkOrder,
    onOpen: () -> Unit,
    onToggleTask: (String, Boolean) -> Unit,
    onUpdateProgress: (String, String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(order.order.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${order.ownerNickname} · ${order.ownerNumber}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusTag(
                    text = when {
                        order.overdue -> "已逾期"
                        order.order.deadlineDate == null -> "无期限"
                        else -> "进行中"
                    },
                )
            }
            Text(
                text = deadlineText(order),
                style = MaterialTheme.typography.bodySmall,
                color = if (order.overdue) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            order.tasks.forEach { task ->
                CompletionRow(
                    title = task.task.title,
                    checked = task.completed,
                    onCheckedChange = { completed -> onToggleTask(task.task.id, completed) },
                    subtitle = if (task.task.progressType == ProgressType.NUMERIC) {
                        "进度 ${task.currentValue.toPlainString()}/" +
                            task.task.targetValue.toPlainString() +
                            (task.task.unit?.let { " $it" } ?: "")
                    } else {
                        ""
                    },
                    trailing = {
                        if (task.task.progressType == ProgressType.NUMERIC) {
                            ProgressDialogButton(
                                initialValue = task.currentValue.toPlainString(),
                                onValue = { onUpdateProgress(task.task.id, it) },
                            )
                        }
                    },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "约定总价 " + (order.order.priceCents?.let(Money::formatCents) ?: "未填写"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpen) { Text("订单详情") }
            }
        }
    }
}

@Composable
private fun ProgressDialogButton(initialValue: String, onValue: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    TextButton(onClick = { show = true }) { Text("更新进度") }
    if (show) {
        var text by rememberSaveable { mutableStateOf(initialValue) }
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("更新数值进度") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("当前值") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        show = false
                        onValue(text)
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { show = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun UndoResultDialog(result: UndoResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("撤销结果") },
        text = {
            Column {
                Text(
                    if (result.alreadyUndone) {
                        "该批量操作已经撤销过。"
                    } else {
                        "已恢复 ${result.restoredCount} 项，批量前已完成的项不会被清除。"
                    },
                )
                if (result.conflicts.isNotEmpty()) {
                    Text(
                        text = "以下项目在批量完成后又被修改，已保留最新结果：",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    result.conflicts.forEach { conflict ->
                        Text(
                            text = "· ${conflict.description}：${conflict.reason}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

private fun deadlineText(order: ShortWorkOrder): String {
    val deadline = order.order.deadlineDate ?: return "无交付期限"
    val days = ChronoUnit.DAYS.between(order.today, deadline)
    return when {
        days < 0 -> "已逾期 ${-days} 天 · ${formatGameDate(deadline)} 截止"
        days == 0L -> "今天截止（${formatGameDate(deadline)}）"
        else -> "${formatShortDate(deadline)} 截止"
    }
}
