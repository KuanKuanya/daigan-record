package com.kuankuan.daigan.feature.accounts

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kuankuan.daigan.data.model.ArchivedAccount
import com.kuankuan.daigan.data.model.OwnerSummary
import com.kuankuan.daigan.ui.components.EmptyStateCard
import com.kuankuan.daigan.ui.components.PageHeader
import com.kuankuan.daigan.ui.components.StatusTag

/** Archived owners and accounts with an explicit restore action. Nothing is deleted. */
@Composable
fun ArchivedRecordsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArchivedRecordsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    ArchivedRecordsScreen(
        uiState = uiState,
        onBack = onBack,
        onRestoreOwner = viewModel::restoreOwner,
        onRestoreAccount = viewModel::restoreAccount,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

@Composable
internal fun ArchivedRecordsScreen(
    uiState: ArchivedRecordsUiState,
    onBack: () -> Unit,
    onRestoreOwner: (String) -> Unit,
    onRestoreAccount: (String) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PageHeader(title = "归档记录", subtitle = "归档不会删除资料，可随时恢复", onBack = onBack)
            when {
                uiState.isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                uiState.error != null -> Box(modifier = Modifier.padding(16.dp)) {
                    EmptyStateCard(title = "无法读取归档记录", description = uiState.error.orEmpty())
                }

                uiState.archivedOwners.isEmpty() && uiState.archivedAccounts.isEmpty() -> Box(
                    modifier = Modifier.padding(16.dp),
                ) {
                    EmptyStateCard(
                        title = "归档记录为空",
                        description = "归档的老板或游戏账号会出现在这里，恢复后重新回到账号信息。",
                    )
                }

                else -> ArchivedList(
                    owners = uiState.archivedOwners,
                    accounts = uiState.archivedAccounts,
                    onRestoreOwner = onRestoreOwner,
                    onRestoreAccount = onRestoreAccount,
                )
            }
        }
    }
}

@Composable
private fun ArchivedList(
    owners: List<OwnerSummary>,
    accounts: List<ArchivedAccount>,
    onRestoreOwner: (String) -> Unit,
    onRestoreAccount: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (owners.isNotEmpty()) {
            item {
                Text(text = "已归档老板", style = MaterialTheme.typography.titleMedium)
            }
            items(owners, key = { "owner-${it.owner.id}" }) { summary ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = summary.owner.nickname,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            StatusTag(text = summary.owner.displayNumber)
                        }
                        Text(
                            text = archivedOwnerAccountSummary(summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Button(
                            onClick = { onRestoreOwner(summary.owner.id) },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text("恢复老板")
                        }
                    }
                }
            }
        }

        if (accounts.isNotEmpty()) {
            item {
                Text(
                    text = "已归档游戏账号",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(accounts, key = { "account-${it.account.id}" }) { archived ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = archived.account.gameName,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "${archived.ownerNickname} · ${archived.ownerNumber} · " +
                                "UID ${archived.account.uid}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Button(
                            onClick = { onRestoreAccount(archived.account.id) },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text("恢复账号")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Describes only what is known: the total count and how many accounts belong to the
 * current owner archive batch. Accounts archived separately (or archived by a legacy
 * version whose provenance cannot be recovered) are not claimed as "archived with the
 * owner", and they stay restorable one by one after the owner is restored.
 */
internal fun archivedOwnerAccountSummary(summary: OwnerSummary): String {
    val total = summary.accounts.size
    val withOwner = summary.accountsArchivedWithOwnerCount
    return when {
        total == 0 -> "归档时没有游戏账号"
        withOwner == total -> "$total 个随老板归档的游戏账号"
        else -> "共 $total 个游戏账号，其中 $withOwner 个随老板归档"
    }
}
