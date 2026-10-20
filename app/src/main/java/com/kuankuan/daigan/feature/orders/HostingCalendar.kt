package com.kuankuan.daigan.feature.orders

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kuankuan.daigan.data.model.OrderDetail
import com.kuankuan.daigan.data.model.ProgressType
import com.kuankuan.daigan.ui.components.CompletionRow
import java.time.LocalDate
import java.time.YearMonth

@Composable
internal fun HostingCalendarCard(
    detail: OrderDetail,
    enabled: Boolean,
    onToggleOccurrence: (String, String, Boolean) -> Unit,
    onUpdateOccurrenceProgress: (String, String, String) -> Unit,
    onCalendarCompletion: ((String, String, LocalDate, String, Boolean) -> Unit)? = null,
) {
    val periods = detail.periods
    if (periods.isEmpty()) return
    val currentPeriod = detail.currentPeriod ?: return
    var periodId by rememberSaveable(detail.order.id) { mutableStateOf(currentPeriod.id) }
    var monthKey by rememberSaveable(detail.order.id) {
        mutableStateOf(YearMonth.from(detail.today.coerceIn(currentPeriod.startDate, currentPeriod.endDate)).toString())
    }
    var selectedDateKey by rememberSaveable(detail.order.id) {
        mutableStateOf(detail.today.coerceIn(currentPeriod.startDate, currentPeriod.endDate).toString())
    }
    var showPeriods by remember { mutableStateOf(false) }

    LaunchedEffect(periods, periodId) {
        if (periods.none { it.id == periodId }) {
            periodId = currentPeriod.id
            monthKey = YearMonth.from(currentPeriod.startDate).toString()
            selectedDateKey = currentPeriod.startDate.toString()
        }
    }

    val period = periods.firstOrNull { it.id == periodId } ?: currentPeriod
    val firstMonth = YearMonth.from(period.startDate)
    val lastMonth = YearMonth.from(period.endDate)
    val month = runCatching { YearMonth.parse(monthKey) }.getOrDefault(firstMonth)
        .coerceIn(firstMonth, lastMonth)
    val selectedDate = runCatching { LocalDate.parse(selectedDateKey) }.getOrDefault(period.startDate)
    val calendar = remember(period, detail.tasks, detail.occurrences, month, detail.today) {
        buildHostingCalendarMonth(period, detail.tasks, detail.occurrences, month, detail.today)
    }
    val selectedDay = calendar.days.firstOrNull { it.date == selectedDate }
        ?.takeIf { it.inPeriod }
    val colors = MaterialTheme.colorScheme

    Card(
        modifier = Modifier.fillMaxWidth().testTag("hosting-calendar"),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("打卡日历", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { showPeriods = true }) {
                        Text("第 ${period.periodIndex + 1} 期 ▾")
                    }
                    DropdownMenu(expanded = showPeriods, onDismissRequest = { showPeriods = false }) {
                        periods.sortedBy { it.periodIndex }.forEach { option ->
                            DropdownMenuItem(
                                text = { Text("第 ${option.periodIndex + 1} 期") },
                                onClick = {
                                    periodId = option.id
                                    val targetDate = detail.today.coerceIn(option.startDate, option.endDate)
                                    monthKey = YearMonth.from(targetDate).toString()
                                    selectedDateKey = targetDate.toString()
                                    showPeriods = false
                                },
                                modifier = Modifier.testTag("hosting-calendar-period-${option.periodIndex}"),
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { monthKey = month.minusMonths(1).toString() },
                    enabled = month > firstMonth,
                    modifier = Modifier.testTag("hosting-calendar-previous-month"),
                ) { Text("‹") }
                Text(
                    text = "%04d-%02d".format(month.year, month.monthValue),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                TextButton(
                    onClick = { monthKey = month.plusMonths(1).toString() },
                    enabled = month < lastMonth,
                    modifier = Modifier.testTag("hosting-calendar-next-month"),
                ) { Text("›") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        color = colors.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            val cells = List(month.atDay(1).dayOfWeek.value - 1) { null } + calendar.days
            cells.chunked(7).forEach { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    week.forEach { day ->
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (day == null) {
                                Box(Modifier.size(38.dp))
                            } else {
                                val chosen = selectedDate == day.date
                                val fillTarget = when (day.status) {
                                    HostingCalendarDayStatus.COMPLETE ->
                                        if (colors.surface.luminance() < 0.5f) Color(0xFF6D5A16) else Color(0xFFFFE273)
                                    HostingCalendarDayStatus.PARTIAL -> colors.secondaryContainer
                                    else -> Color.Transparent
                                }
                                val fill by animateColorAsState(fillTarget, tween(180), label = "calendar-fill")
                                Surface(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .clickable(enabled = day.inPeriod) {
                                            selectedDateKey = day.date.toString()
                                        }
                                        .testTag("hosting-calendar-day-${day.date}"),
                                    shape = CircleShape,
                                    color = fill,
                                    border = when {
                                        day.isToday -> BorderStroke(1.5.dp, colors.onSurface)
                                        chosen -> BorderStroke(1.dp, colors.primary)
                                        day.status == HostingCalendarDayStatus.OPEN -> BorderStroke(1.dp, colors.outlineVariant)
                                        else -> null
                                    },
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            day.date.dayOfMonth.toString(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (day.inPeriod) colors.onSurface else colors.onSurface.copy(alpha = 0.24f),
                                            modifier = Modifier.semantics {
                                                contentDescription = buildString {
                                                    append(day.date.toString())
                                                    append(when (day.status) {
                                                        HostingCalendarDayStatus.COMPLETE -> "，已完成"
                                                        HostingCalendarDayStatus.PARTIAL -> "，部分完成"
                                                        HostingCalendarDayStatus.OPEN -> "，待完成"
                                                        HostingCalendarDayStatus.NONE -> ""
                                                    })
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().background(colors.surfaceContainerLow, CircleShape).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${calendar.completedDayCount} 天", style = MaterialTheme.typography.titleMedium)
                Text("已完成", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp))
            }

            selectedDay?.let { day ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${day.date} · ${day.tasks.count { it.completed }}/${day.tasks.size}",
                        style = MaterialTheme.typography.titleSmall)
                    if (day.tasks.isEmpty()) {
                        Text("当天没有托管任务", style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                    } else {
                        day.tasks.forEach { entry ->
                            CompletionRow(
                                title = entry.task.title,
                                checked = entry.completed,
                                enabled = enabled && !day.date.isAfter(detail.today),
                                onCheckedChange = { completed ->
                                    if (onCalendarCompletion != null) {
                                        onCalendarCompletion(period.id, entry.task.id, day.date, entry.occurrenceKey, completed)
                                    } else {
                                        onToggleOccurrence(entry.task.id, entry.occurrenceKey, completed)
                                    }
                                },
                                subtitle = entry.occurrence?.value?.toPlainString(),
                                trailing = if (entry.task.progressType == ProgressType.NUMERIC) {
                                    {
                                        NumericEditButton(
                                            initialValue = (entry.occurrence?.value ?: entry.task.currentValue).toPlainString(),
                                            enabled = enabled && !day.date.isAfter(detail.today),
                                            onValue = { value ->
                                                onUpdateOccurrenceProgress(entry.task.id, entry.occurrenceKey, value)
                                            },
                                        )
                                    }
                                } else null,
                            )
                        }
                    }
                }
            }
        }
    }
}
