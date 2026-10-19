package com.daigan.record.feature.accounts

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daigan.record.data.model.OwnerSummary
import com.daigan.record.ui.components.EmptyStateCard
import com.daigan.record.ui.components.PageHeader
import com.daigan.record.ui.components.StatusTag

@Composable
fun AccountsScreen(
    onOpenSettings: () -> Unit,
    onOpenOwner: (String) -> Unit,
    onCreateOwner: () -> Unit,
    onOpenArchived: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AccountsScreen(
        uiState = uiState,
        onOpenSettings = onOpenSettings,
        onOpenOwner = onOpenOwner,
        onCreateOwner = onCreateOwner,
        onOpenArchived = onOpenArchived,
        modifier = modifier,
    )
}

@Composable
internal fun AccountsScreen(
    uiState: OwnersUiState,
    onOpenSettings: () -> Unit,
    onOpenOwner: (String) -> Unit,
    onCreateOwner: () -> Unit,
    onOpenArchived: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = "账号信息",
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "设置")
                }
            },
        )
        when (uiState) {
            OwnersUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            is OwnersUiState.Error -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                EmptyStateCard(title = "无法读取资料", description = uiState.message)
            }

            is OwnersUiState.Success -> OwnerList(
                owners = uiState.owners,
                archivedCount = uiState.archivedCount,
                onOpenOwner = onOpenOwner,
                onCreateOwner = onCreateOwner,
                onOpenArchived = onOpenArchived,
            )
        }
    }
}

@Composable
private fun OwnerList(
    owners: List<OwnerSummary>,
    archivedCount: Int,
    onOpenOwner: (String) -> Unit,
    onCreateOwner: () -> Unit,
    onOpenArchived: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The only top-level creation action in this section.
                Button(onClick = onCreateOwner) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(text = "新增", modifier = Modifier.padding(start = 4.dp))
                }
                Box(modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenArchived) {
                    Text(
                        text = if (archivedCount > 0) "归档记录 ($archivedCount)" else "归档记录",
                    )
                }
            }
        }
        if (owners.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "还没有老板资料",
                    description = "新增老板资料并添加游戏账号。",
                )
            }
        }
        items(owners, key = { it.owner.id }) { summary ->
            OwnerCard(summary = summary, onClick = { onOpenOwner(summary.owner.id) })
        }
    }
}

@Composable
private fun OwnerCard(
    summary: OwnerSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().testTag("owner-card-${summary.owner.id}"),
        border = if (summary.unfinishedOrderCount > 0) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (summary.unfinishedOrderCount > 0) {
                    Icon(
                        imageVector = Icons.Filled.PendingActions,
                        contentDescription = "有未完成订单",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp).size(22.dp),
                    )
                }
                Text(
                    text = summary.owner.nickname,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusTag(text = summary.owner.displayNumber)
            }
            if (summary.unfinishedOrderCount > 0) {
                Text(
                    text = "进行中 · ${summary.unfinishedOrderCount} 单",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Text(
                text = if (summary.activeAccountCount == 0) {
                    "暂无游戏账号"
                } else {
                    "${summary.activeAccountCount} 个游戏账号 · " + summary.gameNames.joinToString(" / ")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "查看账号",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Box(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
