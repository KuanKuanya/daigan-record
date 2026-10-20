package com.kuankuan.daigan.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kuankuan.daigan.data.model.Money
import com.kuankuan.daigan.data.model.ReceiptHistoryEntry
import com.kuankuan.daigan.data.model.ReceiptStatus
import com.kuankuan.daigan.ui.components.EmptyStateCard
import com.kuankuan.daigan.ui.components.PageHeader
import com.kuankuan.daigan.ui.components.StatusTag
import com.kuankuan.daigan.ui.components.formatInstant

/**
 * Independent receipt history under Settings. Newest actual receipt first; every row
 * names its owner/account/order/period and marks voided or corrected entries.
 */
@Composable
fun ReceiptHistoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReceiptHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ReceiptHistoryContent(
        state = state,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun ReceiptHistoryContent(
    state: ReceiptHistoryUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = "收款明细",
            subtitle = "全部实收流水，按实际收款时间倒序",
            onBack = onBack,
        )
        when {
            state.loading && state.entries.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.error != null -> Box(modifier = Modifier.padding(16.dp)) {
                EmptyStateCard(title = "无法读取收款明细", description = checkNotNull(state.error))
            }

            state.entries.isEmpty() -> Box(modifier = Modifier.padding(16.dp)) {
                EmptyStateCard(
                    title = "还没有收款记录",
                    description = "在订单详情中记录收款后，这里会按实际收款时间显示金额、渠道、" +
                        "老板、账号、订单与归属期次。",
                )
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        text = "共 ${state.entries.size} 笔（含已作废与已更正）。作废或更正不会删除历史记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(state.entries, key = { it.receipt.id }) { entry ->
                    ReceiptHistoryCard(entry = entry)
                }
            }
        }
    }
}

@Composable
private fun ReceiptHistoryCard(entry: ReceiptHistoryEntry) {
    val receipt = entry.receipt
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Money.formatCents(receipt.amountCents),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                when (receipt.status) {
                    ReceiptStatus.VOIDED -> StatusTag(text = "已作废")
                    ReceiptStatus.REPLACED -> StatusTag(text = "已更正")
                    ReceiptStatus.ACTIVE -> Unit
                }
            }
            Text(
                text = "${receipt.channel.label} · ${formatInstant(receipt.receivedAt)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            receipt.note?.let {
                Text(
                    text = "备注：$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (receipt.status == ReceiptStatus.REPLACED) {
                Text(
                    text = entry.correctedToCents?.let { "已更正为 ${Money.formatCents(it)}" }
                        ?: "已更正（新记录已单独入账）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (receipt.status == ReceiptStatus.VOIDED) {
                Text(
                    text = "已作废，不计入实收与欠款余额。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = entry.ownerLabel,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = entry.accountLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${entry.orderTitle} · ${entry.periodLabel}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
