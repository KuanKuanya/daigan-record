package com.daigan.record.feature.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daigan.record.data.model.OccurrenceRules
import com.daigan.record.ui.components.CompletionRow
import com.daigan.record.ui.components.EmptyStateCard
import com.daigan.record.ui.components.PageHeader
import com.daigan.record.ui.components.StatusTag
import com.daigan.record.ui.components.formatGameDate
import com.daigan.record.ui.components.formatInstant
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackfillScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackfillViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val detail = uiState.detail
            PageHeader(
                title = "补录 / 更正",
                subtitle = detail?.let {
                    "${it.order.title} · ${it.account.gameName} · ${it.account.server}"
                } ?: "历史日期",
                onBack = onBack,
            )

            when {
                uiState.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                uiState.error != null -> Box(modifier = Modifier.padding(16.dp)) {
                    EmptyStateCard(title = "无法补录", description = checkNotNull(uiState.error))
                }

                detail != null -> {
                    val period = detail.periods.firstOrNull { it.id == uiState.periodId }
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                ),
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = period?.let {
                                            "第 ${it.periodIndex + 1} 期 · " +
                                                "${formatGameDate(it.startDate)} – " +
                                                formatGameDate(it.endDate)
                                        } ?: "托管期次",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        text = if (uiState.readOnly) {
                                            "该期次尚未开始，日期只读。"
                                        } else {
                                            "只能选择本期开始日到今天（含今天）的游戏日期；未来日期不可补录。"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = formatGameDate(uiState.selectedDate),
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(
                                            onClick = { showDatePicker = true },
                                            enabled = !uiState.readOnly,
                                        ) {
                                            Text("选择历史日期")
                                        }
                                    }
                                }
                            }
                        }

                        if (uiState.readOnly) {
                            item {
                                EmptyStateCard(
                                    title = "托管期次尚未开始",
                                    description = uiState.readOnlyReason
                                        ?: "未开始的期次不能补录或打卡。",
                                )
                            }
                        } else if (uiState.targets.isEmpty()) {
                            item {
                                EmptyStateCard(
                                    title = "该日期没有需要记录的托管任务",
                                    description = "所选日期不在任务重复周期内，或该期没有配置任务。" +
                                        "每周任务只在对应的游戏周出现一次。",
                                )
                            }
                        } else {
                            items(
                                count = uiState.targets.size,
                                key = { uiState.targets[it].rowKey },
                            ) { index ->
                                val target = uiState.targets[index]
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                    ),
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        CompletionRow(
                                            title = target.task.title,
                                            checked = target.completed,
                                            onCheckedChange = { completed ->
                                                viewModel.toggle(
                                                    taskId = target.task.id,
                                                    weeklyWindowStart = target.slot.weeklyWindowStart,
                                                    completed = completed,
                                                )
                                            },
                                            subtitle = buildString {
                                                val label = OccurrenceRules.windowLabel(target.slot)
                                                append(
                                                    if (label != null) {
                                                        "游戏周 $label"
                                                    } else {
                                                        "游戏日期 ${formatGameDate(target.slot.attributedDate)}"
                                                    },
                                                )
                                                target.occurrence?.recordedAt?.let {
                                                    append(" · 实际记录 ${formatInstant(it)}")
                                                }
                                            },
                                            trailing = {
                                                if (target.backfilled) StatusTag(text = "补录")
                                            },
                                        )
                                        if (target.task.progressType ==
                                            com.daigan.record.data.model.ProgressType.NUMERIC
                                        ) {
                                            Text(
                                                text = "目标值 ${target.task.targetValue.toPlainString()}" +
                                                    (target.task.unit?.let { " $it" } ?: ""),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                ),
                            ) {
                                Text(
                                    text = "补录会同时保存归属的游戏日期/游戏周和实际记录时间，并标记为补录。" +
                                        "每周任务仍归属同一个游戏周，不会按天复制；" +
                                        "“所有托管单完成”不会补写历史遗漏。",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        val firstSelectable = uiState.firstSelectableDate
        val lastSelectable = uiState.lastSelectableDate
        val state = rememberDatePickerState(
            initialSelectedDateMillis = uiState.selectedDate
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
            selectableDates = remember(firstSelectable, lastSelectable) {
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                        val date = Instant.ofEpochMilli(utcTimeMillis)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                        return !date.isBefore(firstSelectable) && !date.isAfter(lastSelectable)
                    }

                    override fun isSelectableYear(year: Int): Boolean =
                        year in firstSelectable.year..lastSelectable.year
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { millis ->
                            viewModel.selectDate(
                                Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate(),
                            )
                        }
                        showDatePicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}
