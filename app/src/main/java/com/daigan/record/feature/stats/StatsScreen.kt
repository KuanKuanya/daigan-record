package com.daigan.record.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.daigan.record.core.time.StatisticsRange
import com.daigan.record.data.model.Money
import com.daigan.record.data.model.Statistics
import com.daigan.record.data.model.StatisticsBucket
import com.daigan.record.ui.components.EmptyStateCard
import com.daigan.record.ui.components.PageHeader
import com.daigan.record.ui.components.StatusTag

@Composable
fun StatsScreen(
    onOpenSettings: () -> Unit,
    onOpenOrder: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refresh()
        }
    }
    StatsContent(
        state = state,
        onRange = viewModel::selectRange,
        onOpenSettings = onOpenSettings,
        onOpenOrder = onOpenOrder,
        modifier = modifier,
    )
}

@Composable
internal fun StatsContent(
    state: StatsUiState,
    onRange: (StatisticsRange) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOrder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = "数据统计",
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "设置")
                }
            },
        )
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.range == StatisticsRange.MONTH,
                    onClick = { onRange(StatisticsRange.MONTH) },
                    label = { Text("本月") },
                )
                FilterChip(
                    selected = state.range == StatisticsRange.ALL,
                    onClick = { onRange(StatisticsRange.ALL) },
                    label = { Text("全部累计") },
                )
            }
        }
        when {
            state.statistics == null && state.loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.statistics == null -> Box(modifier = Modifier.padding(16.dp)) {
                EmptyStateCard(
                    title = "无法读取统计",
                    description = state.error ?: "请稍后重试。",
                )
            }

            else -> StatisticsList(
                statistics = checkNotNull(state.statistics),
                loading = state.loading,
                onOpenOrder = onOpenOrder,
            )
        }
    }
}

@Composable
private fun StatisticsList(
    statistics: Statistics,
    loading: Boolean,
    onOpenOrder: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            IncomeCard(statistics = statistics, loading = loading)
        }
        item {
            ReceiptChartCard(statistics = statistics)
        }
        item {
            OrdersCard(statistics = statistics)
        }
        item {
            OutstandingHeader(statistics = statistics)
        }
        if (statistics.outstandingItems.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "全部收齐",
                    description = "",
                )
            }
        } else {
            items(statistics.outstandingItems, key = { "${it.orderId}-${it.periodId}" }) { item ->
                OutstandingRow(item = item, onClick = { onOpenOrder(item.orderId) })
            }
        }
    }
}

@Composable
private fun IncomeCard(statistics: Statistics, loading: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (statistics.monthly) "${statistics.label}实收" else "累计实收",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("stats-income-label"),
                )
                if (loading) {
                    Text(
                        text = "更新中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = Money.formatCents(statistics.receivedCents),
                style = MaterialTheme.typography.headlineMedium,
            )
            ProportionBar(
                primary = statistics.receivedCents.toFloat(),
                secondary = statistics.outstandingCents.toFloat(),
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Row {
                Text(
                    text = "全部待收",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = Money.formatCents(statistics.outstandingCents),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Row {
                Text(
                    text = if (statistics.monthly) "本月接单" else "累计接单",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${statistics.orderCount} 单",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (statistics.orderCount > 0) {
                ProportionBar(
                    primary = statistics.hostingOrderCount.toFloat(),
                    secondary = statistics.shortOrderCount.toFloat(),
                    modifier = Modifier.padding(top = 8.dp),
                    primaryColor = MaterialTheme.colorScheme.tertiary,
                    secondaryColor = MaterialTheme.colorScheme.primary,
                )
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        text = "托管 ${statistics.hostingOrderCount} 单",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "短期 ${statistics.shortOrderCount} 单",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = "该范围内还没有新建订单。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ReceiptChartCard(statistics: Statistics) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "实收趋势", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (statistics.monthly) {
                    "按本月的周分段统计实收（实际收款时间）"
                } else {
                    "最近六个自然月的实收（实际收款时间）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ReceiptBarChart(
                buckets = statistics.buckets,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun ReceiptBarChart(
    buckets: List<StatisticsBucket>,
    modifier: Modifier = Modifier,
) {
    val maxCents = buckets.maxOfOrNull { it.amountCents } ?: 0L
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        buckets.forEach { bucket ->
            val fraction = if (maxCents <= 0L) 0f else bucket.amountCents.toFloat() / maxCents
            val barHeight = if (maxCents <= 0L) 4.dp else (6f + 88f * fraction).dp
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = "${bucket.label} 实收 ${Money.formatCents(bucket.amountCents)}"
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = compactAmount(bucket.amountCents),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .height(94.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .width(20.dp)
                            .height(barHeight)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (bucket.amountCents > 0L) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            ),
                    )
                }
                Text(
                    text = bucket.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun OrdersCard(statistics: Statistics) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (statistics.monthly) "本月接单构成" else "累计接单构成",
                style = MaterialTheme.typography.titleMedium,
            )
            if (statistics.orderCount == 0) {
                Text(
                    text = "该范围内还没有新建订单；续期不会增加订单数量。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Text(
                    text = "托管 ${statistics.hostingOrderCount} 单 · 短期 ${statistics.shortOrderCount} 单",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                ProportionBar(
                    primary = statistics.hostingOrderCount.toFloat(),
                    secondary = statistics.shortOrderCount.toFloat(),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = "共 ${statistics.orderCount} 单",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun OutstandingHeader(statistics: Statistics) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "待收款",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "全部 ${statistics.outstandingOrderCount} 笔订单",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OutstandingRow(
    item: com.daigan.record.data.model.OutstandingItem,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.ownerNickname,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                StatusTag(text = "待收 ${Money.formatCents(item.outstandingCents)}")
            }
            Text(
                text = "${item.ownerNumber} · ${item.gameName} · ${item.server}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${item.orderTitle} · ${item.periodLabel}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = "应收 ${Money.formatCents(item.priceCents)} · " +
                    "已收 ${Money.formatCents(item.paidCents)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProportionBar(
    primary: Float,
    secondary: Float,
    modifier: Modifier = Modifier,
    primaryColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    secondaryColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val total = primary + secondary
    if (total <= 0f) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(6.dp)),
    ) {
        Box(
            modifier = Modifier
                .weight(primary.coerceAtLeast(if (secondary == 0f) 1f else 0.0001f))
                .fillMaxSize()
                .background(primaryColor),
        )
        if (secondary > 0f) {
            Box(
                modifier = Modifier
                    .weight(secondary)
                    .fillMaxSize()
                    .background(secondaryColor),
            )
        }
    }
}

private fun compactAmount(cents: Long): String =
    if (cents % 100 == 0L) "¥${cents / 100}" else Money.formatCents(cents)
