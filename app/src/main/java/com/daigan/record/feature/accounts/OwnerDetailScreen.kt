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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daigan.record.data.model.GameAccount
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.data.model.OwnerDetail
import com.daigan.record.ui.components.EmptyStateCard
import com.daigan.record.ui.components.LabeledValue
import com.daigan.record.ui.components.PageHeader
import com.daigan.record.ui.components.StatusTag
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun OwnerDetailScreen(
    onBack: () -> Unit,
    onEditOwner: (String) -> Unit,
    onAddAccount: (String) -> Unit,
    onEditAccount: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OwnerDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (val state = uiState) {
            OwnerDetailUiState.Loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            OwnerDetailUiState.NotFound -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                PageHeader(title = "老板详情", onBack = onBack)
                Box(modifier = Modifier.padding(16.dp)) {
                    EmptyStateCard(
                        title = "找不到这位老板",
                        description = "该老板可能已被归档，请在“归档记录”中查看。",
                    )
                }
            }

            is OwnerDetailUiState.Error -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                PageHeader(title = "老板详情", onBack = onBack)
                Box(modifier = Modifier.padding(16.dp)) {
                    EmptyStateCard(title = "无法读取资料", description = state.message)
                }
            }

            is OwnerDetailUiState.Success -> OwnerDetailContent(
                detail = state.detail,
                onBack = onBack,
                onEditOwner = { onEditOwner(state.detail.owner.id) },
                onAddAccount = { onAddAccount(state.detail.owner.id) },
                onEditAccount = onEditAccount,
                onCopy = { value ->
                    clipboard.setText(AnnotatedString(value))
                    scope.launch { snackbarHostState.showSnackbar("已复制") }
                },
                onArchiveOwner = viewModel::archiveOwner,
                onRestoreOwner = viewModel::restoreOwner,
                onArchiveAccount = viewModel::archiveAccount,
                onRestoreAccount = viewModel::restoreAccount,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
internal fun OwnerDetailContent(
    detail: OwnerDetail,
    onBack: () -> Unit,
    onEditOwner: () -> Unit,
    onAddAccount: () -> Unit,
    onEditAccount: (String, String) -> Unit,
    onCopy: (String) -> Unit,
    onArchiveOwner: () -> Unit,
    onRestoreOwner: () -> Unit,
    onArchiveAccount: (String) -> Unit,
    onRestoreAccount: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showArchiveOwnerDialog by rememberSaveable { mutableStateOf(false) }
    val archived = detail.owner.archived

    if (showArchiveOwnerDialog) {
        AlertDialog(
            onDismissRequest = { showArchiveOwnerDialog = false },
            title = { Text("归档老板？") },
            text = {
                Text(
                    "归档后这位老板及其账号会从“账号信息”移到归档记录，资料不会被删除，" +
                        "随时可以恢复。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showArchiveOwnerDialog = false
                        onArchiveOwner()
                    },
                ) { Text("归档") }
            },
            dismissButton = {
                TextButton(onClick = { showArchiveOwnerDialog = false }) { Text("取消") }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = detail.owner.nickname,
            subtitle = "编号 ${detail.owner.displayNumber}",
            onBack = onBack,
            actions = {
                if (!archived) {
                    IconButton(onClick = onEditOwner) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑昵称")
                    }
                    IconButton(onClick = { showArchiveOwnerDialog = true }) {
                        Icon(Icons.Filled.Archive, contentDescription = "归档老板")
                    }
                } else {
                    IconButton(onClick = onRestoreOwner) {
                        Icon(Icons.Filled.Unarchive, contentDescription = "恢复老板")
                    }
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        LabeledValue(label = "老板编号（固定）", value = detail.owner.displayNumber)
                        Text(
                            text = "编号由系统自动分配，修改昵称不会改变编号。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            text = "创建于 ${formatDate(detail.owner.createdAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        if (archived) {
                            Text(
                                text = "该老板已归档，恢复后可继续编辑账号。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "游戏账号", style = MaterialTheme.typography.titleMedium)
                    Box(modifier = Modifier.weight(1f))
                    if (!archived) {
                        TextButton(onClick = onAddAccount) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text(text = "新增账号", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }

            if (detail.activeAccounts.isEmpty()) {
                item {
                    EmptyStateCard(
                        title = "还没有游戏账号",
                        description = if (archived) {
                            "该老板没有未归档的游戏账号。"
                        } else {
                            "点击“新增账号”记录游戏、区服、UID 与登录方式。"
                        },
                    )
                }
            }

            items(detail.activeAccounts, key = { it.id }) { account ->
                AccountCard(
                    account = account,
                    readOnly = archived,
                    onEdit = { onEditAccount(account.ownerId, account.id) },
                    onArchive = { onArchiveAccount(account.id) },
                    onRestore = { onRestoreAccount(account.id) },
                    onCopy = onCopy,
                )
            }

            item {
                Text(
                    text = "订单",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                EmptyStateCard(
                    title = "暂无订单",
                    description = "订单、托管期次、打卡与收款将在第二阶段接入真实数据。" +
                        "当前版本不会显示虚构的进行中订单或待收金额。",
                )
            }

            if (detail.archivedAccounts.isNotEmpty()) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = "已归档账号 · ${detail.archivedAccounts.size} 个",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(detail.archivedAccounts, key = { "archived-${it.id}" }) { account ->
                    AccountCard(
                        account = account,
                        readOnly = true,
                        onEdit = {},
                        onArchive = {},
                        onRestore = { onRestoreAccount(account.id) },
                        onCopy = onCopy,
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountCard(
    account: GameAccount,
    readOnly: Boolean,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(account.id) { mutableStateOf(false) }
    var passwordVisible by rememberSaveable(account.id) { mutableStateOf(false) }
    var showArchiveDialog by rememberSaveable(account.id) { mutableStateOf(false) }

    if (showArchiveDialog) {
        AlertDialog(
            onDismissRequest = { showArchiveDialog = false },
            title = { Text("归档游戏账号？") },
            text = {
                Text("归档后账号会移到归档记录，资料不会被删除，可随时恢复。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showArchiveDialog = false
                        onArchive()
                    },
                ) { Text("归档") }
            },
            dismissButton = {
                TextButton(onClick = { showArchiveDialog = false }) { Text("取消") }
            },
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = account.gameName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusTag(text = account.server)
            }

            LabeledValue(
                label = "游戏 UID",
                value = account.uid,
                modifier = Modifier.padding(top = 8.dp),
                trailing = {
                    IconButton(onClick = { onCopy(account.uid) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "复制 UID")
                    }
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "登录信息 · " + if (account.hasStoredPassword) "账号密码" else "验证码登录",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起登录信息" else "展开登录信息",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                LabeledValue(
                    label = "登录账号",
                    value = account.loginAccount,
                    trailing = {
                        IconButton(onClick = { onCopy(account.loginAccount) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "复制登录账号")
                        }
                    },
                )
                if (account.loginMethod == LoginMethod.PASSWORD) {
                    LabeledValue(
                        label = "密码",
                        value = if (passwordVisible) account.password.orEmpty() else "••••••",
                        modifier = Modifier.padding(top = 8.dp),
                        trailing = {
                            Row {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) {
                                            Icons.Filled.VisibilityOff
                                        } else {
                                            Icons.Filled.Visibility
                                        },
                                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                                    )
                                }
                                IconButton(onClick = { onCopy(account.password.orEmpty()) }) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = "复制密码")
                                }
                            }
                        },
                    )
                } else {
                    Text(
                        text = "使用验证码登录，不保存验证码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            if (!readOnly) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(text = "为这个账号接新单", modifier = Modifier.padding(start = 6.dp))
                }
                Text(
                    text = "订单功能将在第二阶段提供，当前入口不可用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    TextButton(onClick = onEdit) { Text("编辑账号") }
                    Box(modifier = Modifier.weight(1f))
                    TextButton(onClick = { showArchiveDialog = true }) { Text("归档账号") }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "已归档",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onRestore) { Text("恢复") }
                }
            }
        }
    }
}

private fun formatDate(instant: Instant): String =
    DateTimeFormatter.ofPattern("yyyy.MM.dd")
        .withZone(ZoneId.systemDefault())
        .format(instant)
