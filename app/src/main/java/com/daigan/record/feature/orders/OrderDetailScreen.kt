package com.daigan.record.feature.orders

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.daigan.record.core.time.HostingStatus
import com.daigan.record.data.model.Attachment
import com.daigan.record.data.model.HostingPeriod
import com.daigan.record.data.model.Money
import com.daigan.record.data.model.OrderBalance
import com.daigan.record.data.model.OrderDetail
import com.daigan.record.data.model.OrderHistoryEntry
import com.daigan.record.data.model.OrderTask
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.Receipt
import com.daigan.record.data.model.ReceiptStatus
import com.daigan.record.data.model.UndoResult
import com.daigan.record.ui.components.CompletionRow
import com.daigan.record.ui.components.EmptyStateCard
import com.daigan.record.ui.components.LabeledValue
import com.daigan.record.ui.components.PageHeader
import com.daigan.record.ui.components.ScreenshotStrip
import com.daigan.record.ui.components.StatusTag
import com.daigan.record.ui.components.formatGameDate
import com.daigan.record.ui.components.formatInstant
import com.daigan.record.ui.components.formatShortDate
import java.io.File
import java.time.LocalDate
import java.util.UUID

@Composable
fun OrderDetailScreen(
    onBack: () -> Unit,
    onBackfill: (periodId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OrderDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showRenewDialog by rememberSaveable { mutableStateOf(false) }
    var renewalOperationId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }

    // Payment/correction dialog state. Operation ids are created once per dialog and
    // survive a failed submission so a retry is idempotent.
    var showPaymentDialog by rememberSaveable { mutableStateOf(false) }
    var paymentPeriodId by rememberSaveable { mutableStateOf<String?>(null) }
    var paymentOperationId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var amendReceiptId by rememberSaveable { mutableStateOf<String?>(null) }
    var correctionOperationId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var voidReceiptId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        viewModel.renewalApplied.collect {
            showRenewDialog = false
            renewalOperationId = UUID.randomUUID().toString()
        }
    }
    LaunchedEffect(Unit) {
        viewModel.financialApplied.collect {
            showPaymentDialog = false
            amendReceiptId = null
            voidReceiptId = null
            paymentOperationId = UUID.randomUUID().toString()
            correctionOperationId = UUID.randomUUID().toString()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refresh()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val state = uiState
        when {
            state.loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.error != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                PageHeader(title = "订单详情", onBack = onBack)
                Box(modifier = Modifier.padding(16.dp)) {
                    EmptyStateCard(title = "无法读取订单", description = checkNotNull(state.error))
                }
            }

            state.detail != null -> OrderDetailContent(
                detail = checkNotNull(state.detail),
                lastBatchId = state.lastBatchId,
                undoResult = state.undoResult,
                onBack = onBack,
                onToggleShortTask = viewModel::toggleShortTask,
                onUpdateShortProgress = viewModel::updateShortProgress,
                onCompleteShortOrder = viewModel::completeShortOrder,
                onToggleOccurrence = viewModel::toggleOccurrence,
                onCalendarCompletion = viewModel::setCalendarCompletion,
                onUpdateOccurrenceProgress = viewModel::updateOccurrenceProgress,
                onCompleteHostingOrder = viewModel::completeHostingOrder,
                onUndo = viewModel::undoLastBatch,
                onClearUndoResult = viewModel::clearUndoResult,
                onAddAttachment = viewModel::addAttachment,
                onReplaceAttachment = viewModel::replaceAttachment,
                onDeleteAttachment = viewModel::deleteAttachment,
                fileFor = viewModel::fileFor,
                onBackfill = onBackfill,
                onRenewClick = {
                    renewalOperationId = UUID.randomUUID().toString()
                    showRenewDialog = true
                },
                onRecordPayment = { periodId ->
                    paymentPeriodId = periodId
                    paymentOperationId = UUID.randomUUID().toString()
                    showPaymentDialog = true
                },
                onAmendReceipt = { receipt ->
                    correctionOperationId = UUID.randomUUID().toString()
                    amendReceiptId = receipt.id
                },
                onVoidReceipt = { receipt ->
                    correctionOperationId = UUID.randomUUID().toString()
                    voidReceiptId = receipt.id
                },
                paymentInFlight = state.paymentInFlight,
                modifier = Modifier.padding(padding),
            )
        }
    }

    val detail = uiState.detail
    if (showRenewDialog && detail != null && detail.isHosting) {
        RenewalDialog(
            detail = detail,
            operationId = renewalOperationId,
            onDismiss = { showRenewDialog = false },
            onConfirm = { operationId, days, price ->
                viewModel.renew(operationId, days, price)
            },
        )
    }

    if (showPaymentDialog && detail != null) {
        PaymentDialog(
            orderTitle = detail.order.title,
            ownerLabel = "${detail.owner.nickname} · ${detail.owner.displayNumber}",
            accountLabel = "${detail.account.gameName} · ${detail.account.server}",
            balance = detail.balance(),
            initialPeriodId = paymentPeriodId,
            now = viewModel.now(),
            operationId = paymentOperationId,
            inFlight = uiState.paymentInFlight,
            onDismiss = { if (!uiState.paymentInFlight) showPaymentDialog = false },
            onConfirm = { request ->
                viewModel.recordPayment(
                    periodId = request.periodId,
                    amountYuan = Money.centsToYuanText(request.amountCents),
                    receivedAt = request.receivedAt,
                    channel = request.channel,
                    note = request.note.orEmpty(),
                    operationId = request.operationId,
                )
            },
        )
    }

    val amendReceipt = amendReceiptId?.let { id -> detail?.receipts?.firstOrNull { it.id == id } }
    if (amendReceipt != null && detail != null) {
        AmendReceiptDialog(
            receipt = amendReceipt,
            periodLabel = periodLabelFor(detail, amendReceipt.periodId),
            inFlight = uiState.paymentInFlight,
            onDismiss = { if (!uiState.paymentInFlight) amendReceiptId = null },
            onConfirm = { amount, receivedAt, channel, note, reason ->
                viewModel.amendReceipt(
                    receiptId = amendReceipt.id,
                    amountYuan = amount,
                    receivedAt = receivedAt,
                    channel = channel,
                    note = note,
                    reason = reason,
                    operationId = correctionOperationId,
                )
            },
        )
    }

    val voidReceipt = voidReceiptId?.let { id -> detail?.receipts?.firstOrNull { it.id == id } }
    if (voidReceipt != null && detail != null) {
        VoidReceiptDialog(
            receipt = voidReceipt,
            periodLabel = periodLabelFor(detail, voidReceipt.periodId),
            inFlight = uiState.paymentInFlight,
            onDismiss = { if (!uiState.paymentInFlight) voidReceiptId = null },
            onConfirm = { reason ->
                viewModel.voidReceipt(
                    receiptId = voidReceipt.id,
                    reason = reason,
                    operationId = correctionOperationId,
                )
            },
        )
    }
}

private fun periodLabelFor(detail: OrderDetail, periodId: String?): String = if (periodId == null) {
    "短期订单"
} else {
    val period = detail.periods.firstOrNull { it.id == periodId }
    if (period == null) {
        "托管期次"
    } else {
        "第 ${period.periodIndex + 1} 期 · ${formatGameDate(period.startDate)}–${formatGameDate(period.endDate)}"
    }
}

@Composable
internal fun OrderDetailContent(
    detail: OrderDetail,
    lastBatchId: String?,
    undoResult: UndoResult?,
    onBack: () -> Unit,
    onToggleShortTask: (String, Boolean) -> Unit,
    onUpdateShortProgress: (String, String) -> Unit,
    onCompleteShortOrder: () -> Unit,
    onToggleOccurrence: (String, String, Boolean) -> Unit,
    onUpdateOccurrenceProgress: (String, String, String) -> Unit,
    onCompleteHostingOrder: () -> Unit,
    onUndo: () -> Unit,
    onClearUndoResult: () -> Unit,
    onAddAttachment: (String?, String?, Uri) -> Unit,
    onReplaceAttachment: (String, Uri) -> Unit,
    onDeleteAttachment: (String) -> Unit,
    fileFor: (Attachment) -> File,
    onBackfill: (String) -> Unit,
    onRenewClick: () -> Unit,
    onRecordPayment: (periodId: String?) -> Unit,
    onAmendReceipt: (Receipt) -> Unit,
    onVoidReceipt: (Receipt) -> Unit,
    paymentInFlight: Boolean,
    modifier: Modifier = Modifier,
    onCalendarCompletion: ((String, String, java.time.LocalDate, String, Boolean) -> Unit)? = null,
) {
    val canWrite = !detail.owner.archived && !detail.account.archived
    // Money stays writable for archived records: debt must remain collectible without
    // reactivating any archived task.
    val canPay = !paymentInFlight
    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = detail.order.title,
            subtitle = "${detail.owner.nickname} · ${detail.owner.displayNumber} / " +
                "${detail.account.gameName} · ${detail.account.server}",
            onBack = onBack,
        )
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().testTag("order-detail-list"),
        ) {
            item {
                OrderSummaryCard(detail = detail)
            }

            if (detail.isHosting) {
                item {
                    HostingCalendarCard(
                        detail = detail,
                        enabled = canWrite,
                        onToggleOccurrence = onToggleOccurrence,
                        onUpdateOccurrenceProgress = onUpdateOccurrenceProgress,
                        onCalendarCompletion = onCalendarCompletion,
                    )
                }
                hostingSections(
                    detail = detail,
                    canWrite = canWrite,
                    lastBatchId = lastBatchId,
                    onToggleOccurrence = onToggleOccurrence,
                    onUpdateOccurrenceProgress = onUpdateOccurrenceProgress,
                    onCompleteHostingOrder = onCompleteHostingOrder,
                    onUndo = onUndo,
                    onAddAttachment = onAddAttachment,
                    onReplaceAttachment = onReplaceAttachment,
                    onDeleteAttachment = onDeleteAttachment,
                    fileFor = fileFor,
                    onBackfill = onBackfill,
                    onRenewClick = onRenewClick,
                    onRecordPayment = onRecordPayment,
                    onAmendReceipt = onAmendReceipt,
                    onVoidReceipt = onVoidReceipt,
                    canPay = canPay,
                )
            } else {
                shortSections(
                    detail = detail,
                    canWrite = canWrite,
                    lastBatchId = lastBatchId,
                    onToggleShortTask = onToggleShortTask,
                    onUpdateShortProgress = onUpdateShortProgress,
                    onCompleteShortOrder = onCompleteShortOrder,
                    onUndo = onUndo,
                    onAddAttachment = onAddAttachment,
                    onReplaceAttachment = onReplaceAttachment,
                    onDeleteAttachment = onDeleteAttachment,
                    fileFor = fileFor,
                    onRecordPayment = onRecordPayment,
                    onAmendReceipt = onAmendReceipt,
                    onVoidReceipt = onVoidReceipt,
                    canPay = canPay,
                )
            }

        }
    }

    undoResult?.let { result ->
        AlertDialog(
            onDismissRequest = onClearUndoResult,
            title = { Text("撤销结果") },
            text = {
                Column {
                    Text(
                        if (result.alreadyUndone) {
                            "该批量操作已经撤销过。"
                        } else {
                            "已恢复 ${result.restoredCount} 项。"
                        },
                    )
                    if (result.conflicts.isNotEmpty()) {
                        Text(
                            text = "以下记录在批量完成后又被修改，已保留最新结果：",
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
            confirmButton = {
                TextButton(onClick = onClearUndoResult) { Text("知道了") }
            },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.hostingSections(
    detail: OrderDetail,
    canWrite: Boolean,
    lastBatchId: String?,
    onToggleOccurrence: (String, String, Boolean) -> Unit,
    onUpdateOccurrenceProgress: (String, String, String) -> Unit,
    onCompleteHostingOrder: () -> Unit,
    onUndo: () -> Unit,
    onAddAttachment: (String?, String?, Uri) -> Unit,
    onReplaceAttachment: (String, Uri) -> Unit,
    onDeleteAttachment: (String) -> Unit,
    fileFor: (Attachment) -> File,
    onBackfill: (String) -> Unit,
    onRenewClick: () -> Unit,
    onRecordPayment: (periodId: String?) -> Unit,
    onAmendReceipt: (Receipt) -> Unit,
    onVoidReceipt: (Receipt) -> Unit,
    canPay: Boolean,
) {
    val currentPeriod = detail.currentPeriod
    val status = detail.currentStatus()
    val currentTasks = detail.currentHostingTasks()

    item {
        if (currentPeriod == null) {
            EmptyStateCard(title = "托管期次缺失", description = "该订单没有可用的托管期次。")
        } else {
            PeriodCard(
                period = currentPeriod,
                status = status,
                isCurrent = true,
                title = "本期托管",
            )
        }
    }

    if (status == HostingStatus.EXPIRED) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "已到期 · 待续期",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = "到期后不再生成新任务；历史打卡、截图与期次价格全部保留，可继续补录或查看。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Button(
                        onClick = onRenewClick,
                        enabled = canWrite,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) { Text("续期") }
                }
            }
        }
    } else if (status == HostingStatus.ACTIVE) {
        item {
            Text(
                text = "续期只能在当前期到期后进行，避免新期与旧期重叠。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    item {
        Text(text = "今日打卡", style = MaterialTheme.typography.titleMedium)
        if (status == HostingStatus.NOT_STARTED) {
            Text(
                text = "托管尚未开始，不会提前生成任务。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (status == HostingStatus.ACTIVE) {
        if (currentTasks.isEmpty()) {
            item {
                Text(
                    text = "今天没有需要完成的托管任务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        currentTasks.forEach { item ->
            item(key = "current-${item.task.id}") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        CompletionRow(
                            title = item.task.title,
                            checked = item.completed,
                            enabled = canWrite,
                            onCheckedChange = {
                                onToggleOccurrence(item.task.id, item.slot.key, it)
                            },
                            subtitle = taskSubtitle(item.task, item.windowLabel, item.value),
                            trailing = {
                                if (item.task.progressType == ProgressType.NUMERIC) {
                                    NumericEditButton(
                                        initialValue = (item.value ?: item.task.currentValue).toPlainString(),
                                        enabled = canWrite,
                                        onValue = {
                                            onUpdateOccurrenceProgress(
                                                item.task.id,
                                                item.slot.key,
                                                it,
                                            )
                                        },
                                    )
                                }
                            },
                        )
                        if (item.occurrenceId != null) {
                            ScreenshotStrip(
                                attachments = detail.attachmentsForOccurrence(item.occurrenceId),
                                fileFor = fileFor,
                                enabled = canWrite,
                                onAdd = { uri -> onAddAttachment(null, item.occurrenceId, uri) },
                                onReplace = { attachment, uri ->
                                    onReplaceAttachment(attachment.id, uri)
                                },
                                onDelete = { attachment -> onDeleteAttachment(attachment.id) },
                                modifier = Modifier.padding(top = 8.dp),
                                emptyLabel = "本次打卡还没有截图。",
                            )
                        } else {
                            Text(
                                text = "勾选完成后可添加本次打卡截图。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onCompleteHostingOrder,
                    enabled = canWrite && currentTasks.any { !it.completed },
                ) { Text("整单完成") }
                if (lastBatchId != null) {
                    TextButton(onClick = onUndo) { Text("撤销本次批量完成") }
                }
            }
        }
    }

    item {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "本期打卡记录",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (currentPeriod != null && canWrite) {
                TextButton(onClick = { onBackfill(currentPeriod.id) }) {
                    Text("补录/更正")
                }
            }
        }
    }

    if (currentPeriod != null) {
        val entries = detail.historyEntries().filter { it.periodId == currentPeriod.id }
        if (entries.isEmpty()) {
            item {
                Text(
                    text = "本期暂无打卡记录。批量完成不会补写历史遗漏。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        entries.forEach { entry ->
            item(key = "history-${entry.taskId}-${entry.attributedDate}") {
                HistoryCard(
                    entry = entry,
                    canWrite = canWrite,
                    onAddAttachment = onAddAttachment,
                    onReplaceAttachment = onReplaceAttachment,
                    onDeleteAttachment = onDeleteAttachment,
                    fileFor = fileFor,
                )
            }
        }
    }

    val previous = detail.periods.drop(1)
    if (previous.isNotEmpty()) {
        item {
            HorizontalDivider()
            Text(text = "往期记录", style = MaterialTheme.typography.titleMedium)
        }
        previous.forEach { period ->
            item(key = "period-${period.id}") {
                PreviousPeriodCard(
                    period = period,
                    tasks = detail.tasksForPeriod(period.id),
                    entries = detail.historyEntries().filter { it.periodId == period.id },
                    canWrite = canWrite,
                    onBackfill = onBackfill,
                    onAddAttachment = onAddAttachment,
                    onReplaceAttachment = onReplaceAttachment,
                    onDeleteAttachment = onDeleteAttachment,
                    fileFor = fileFor,
                )
            }
        }
    }

    item {
        PaymentSection(
            detail = detail,
            canPay = canPay,
            onRecordPayment = onRecordPayment,
            onAmendReceipt = onAmendReceipt,
            onVoidReceipt = onVoidReceipt,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.shortSections(
    detail: OrderDetail,
    canWrite: Boolean,
    lastBatchId: String?,
    onToggleShortTask: (String, Boolean) -> Unit,
    onUpdateShortProgress: (String, String) -> Unit,
    onCompleteShortOrder: () -> Unit,
    onUndo: () -> Unit,
    onAddAttachment: (String?, String?, Uri) -> Unit,
    onReplaceAttachment: (String, Uri) -> Unit,
    onDeleteAttachment: (String) -> Unit,
    fileFor: (Attachment) -> File,
    onRecordPayment: (periodId: String?) -> Unit,
    onAmendReceipt: (Receipt) -> Unit,
    onVoidReceipt: (Receipt) -> Unit,
    canPay: Boolean,
) {
    item {
        Card(
            modifier = Modifier.testTag("order-detail-summary"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (detail.order.completed) "已完成" else "进行中",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    StatusTag(text = if (detail.order.completed) "完成" else "未完成")
                }
                LabeledValue(
                    label = "约定总价",
                    value = detail.order.priceCents?.let(Money::formatCents) ?: "未填写",
                    modifier = Modifier.padding(top = 8.dp),
                )
                LabeledValue(
                    label = "交付期限",
                    value = detail.order.deadlineDate?.let(::formatGameDate) ?: "无期限",
                )
                LabeledValue(
                    label = "收款方式",
                    value = when (detail.order.paymentMode) {
                        PaymentMode.ONE_TIME -> "一次结清"
                        PaymentMode.INSTALLMENTS -> "分批收款"
                    },
                )
                if (!detail.order.notes.isNullOrBlank()) {
                    LabeledValue(label = "备注", value = detail.order.notes.orEmpty())
                }
            }
        }
    }

    item {
        Text(text = "任务与进度", style = MaterialTheme.typography.titleMedium)
    }

    detail.tasksForPeriod(null).forEach { task ->
        item(key = "short-${task.id}") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    CompletionRow(
                        title = task.title,
                        checked = task.completed,
                        enabled = canWrite,
                        onCheckedChange = { onToggleShortTask(task.id, it) },
                        subtitle = numericSubtitle(task),
                        trailing = {
                            if (task.progressType == ProgressType.NUMERIC) {
                                NumericEditButton(
                                    initialValue = task.currentValue.toPlainString(),
                                    enabled = canWrite,
                                    onValue = { onUpdateShortProgress(task.id, it) },
                                )
                            }
                        },
                    )
                    ScreenshotStrip(
                        attachments = detail.attachmentsForTask(task.id),
                        fileFor = fileFor,
                        enabled = canWrite,
                        onAdd = { uri -> onAddAttachment(task.id, null, uri) },
                        onReplace = { attachment, uri -> onReplaceAttachment(attachment.id, uri) },
                        onDelete = { attachment -> onDeleteAttachment(attachment.id) },
                        modifier = Modifier.padding(top = 4.dp),
                        emptyLabel = "该任务还没有截图。",
                    )
                }
            }
        }
    }

    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onCompleteShortOrder,
                enabled = canWrite && detail.tasksForPeriod(null).any { !it.completed },
            ) { Text("整单完成") }
            if (lastBatchId != null) {
                TextButton(onClick = onUndo) { Text("撤销本次批量完成") }
            }
        }
    }

    item {
        Text(text = "完成记录", style = MaterialTheme.typography.titleMedium)
    }
    val completed = detail.tasksForPeriod(null).filter { it.completed }
    if (completed.isEmpty()) {
        item {
            Text(
                text = "还没有已完成的任务。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        completed.forEach { task ->
            item(key = "short-history-${task.id}") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(task.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = task.completedAt?.let { formatInstant(it) } ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    item {
        PaymentSection(
            detail = detail,
            canPay = canPay,
            onRecordPayment = onRecordPayment,
            onAmendReceipt = onAmendReceipt,
            onVoidReceipt = onVoidReceipt,
        )
    }
}

@Composable
private fun OrderSummaryCard(detail: OrderDetail) {
    Card(
        modifier = Modifier.testTag("order-detail-summary"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "订单详情",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusTag(
                    text = when {
                        detail.order.isShort && detail.order.completed -> "已完成"
                        detail.order.isShort -> "进行中"
                        detail.currentStatus() == HostingStatus.EXPIRED -> "已到期"
                        detail.currentStatus() == HostingStatus.NOT_STARTED -> "未开始"
                        else -> "托管中"
                    },
                )
            }
            LabeledValue(
                label = "游戏账号",
                value = "${detail.account.gameName} · ${detail.account.server} · ${detail.account.uid}",
                modifier = Modifier.padding(top = 8.dp),
            )
            val period = detail.currentPeriod
            if (period != null) {
                LabeledValue(
                    label = "本期期限（含首尾）",
                    value = "${formatGameDate(period.startDate)} – ${formatGameDate(period.endDate)}" +
                        " · ${period.durationDays} 天",
                )
                LabeledValue(
                    label = "本期约定价格",
                    value = Money.formatCents(period.priceCents),
                )
                LabeledValue(
                    label = "期次",
                    value = "第 ${period.periodIndex + 1} 期",
                )
                Text(
                    text = "游戏时区 ${period.refresh.zoneId} · 每日刷新 " +
                        "%02d:%02d".format(
                            period.refresh.dailyRefresh.hour,
                            period.refresh.dailyRefresh.minute,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (!detail.order.notes.isNullOrBlank()) {
                LabeledValue(
                    label = "订单备注",
                    value = detail.order.notes.orEmpty(),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun PeriodCard(
    period: HostingPeriod,
    status: HostingStatus?,
    isCurrent: Boolean,
    title: String,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$title · 第 ${period.periodIndex + 1} 期",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                StatusTag(
                    text = when (status) {
                        HostingStatus.EXPIRED -> "已到期 · 待续期"
                        HostingStatus.NOT_STARTED -> "未开始"
                        HostingStatus.ACTIVE -> "托管中"
                        null -> "历史"
                    },
                )
            }
            Text(
                text = "${formatGameDate(period.startDate)} – ${formatGameDate(period.endDate)} " +
                    "（含首尾 ${period.durationDays} 天）",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = "本期价格 ${Money.formatCents(period.priceCents)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!isCurrent) {
                Text(
                    text = "往期价格与任务快照已保留，不会随续期改写。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PreviousPeriodCard(
    period: HostingPeriod,
    tasks: List<OrderTask>,
    entries: List<OrderHistoryEntry>,
    canWrite: Boolean,
    onBackfill: (String) -> Unit,
    onAddAttachment: (String?, String?, Uri) -> Unit,
    onReplaceAttachment: (String, Uri) -> Unit,
    onDeleteAttachment: (String) -> Unit,
    fileFor: (Attachment) -> File,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            PeriodCard(period = period, status = null, isCurrent = false, title = "往期")
            Text(
                text = "任务快照",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            tasks.forEach { task ->
                Text(
                    text = "· ${task.title}（${recurrenceText(task)}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "打卡记录 · ${entries.size} 条",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (canWrite) {
                    TextButton(onClick = { onBackfill(period.id) }) { Text("补录/更正") }
                }
            }
            entries.forEach { entry ->
                HistoryCard(
                    entry = entry,
                    canWrite = canWrite,
                    onAddAttachment = onAddAttachment,
                    onReplaceAttachment = onReplaceAttachment,
                    onDeleteAttachment = onDeleteAttachment,
                    fileFor = fileFor,
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(
    entry: OrderHistoryEntry,
    canWrite: Boolean,
    onAddAttachment: (String?, String?, Uri) -> Unit,
    onReplaceAttachment: (String, Uri) -> Unit,
    onDeleteAttachment: (String) -> Unit,
    fileFor: (Attachment) -> File,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.taskTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (entry.backfilled) {
                    StatusTag(text = "补录")
                }
            }
            Text(
                text = if (entry.windowStart != null && entry.windowEnd != null &&
                    entry.windowStart != entry.windowEnd
                ) {
                    "游戏周 ${formatGameDate(entry.windowStart)} – ${formatGameDate(entry.windowEnd)}"
                } else {
                    "游戏日期 ${formatGameDate(entry.attributedDate)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "记录时间 ${formatInstant(entry.recordedAt)}" +
                    if (entry.value != null) " · 进度 ${entry.value.toPlainString()}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val occurrenceId = entry.occurrenceId
            ScreenshotStrip(
                attachments = entry.attachments,
                fileFor = fileFor,
                enabled = canWrite && occurrenceId != null,
                onAdd = { uri -> onAddAttachment(null, occurrenceId, uri) },
                onReplace = { attachment, uri -> onReplaceAttachment(attachment.id, uri) },
                onDelete = { attachment -> onDeleteAttachment(attachment.id) },
                modifier = Modifier.padding(top = 4.dp),
                emptyLabel = "本次打卡还没有截图。",
            )
        }
    }
}

/**
 * Current-period debt, historical debt and total debt, plus every receipt grouped by
 * the period it was explicitly paid against. All numbers come from [OrderDetail.balance].
 */
@Composable
private fun PaymentSection(
    detail: OrderDetail,
    canPay: Boolean,
    onRecordPayment: (periodId: String?) -> Unit,
    onAmendReceipt: (Receipt) -> Unit,
    onVoidReceipt: (Receipt) -> Unit,
) {
    val balance = detail.balance()
    val current = balance.currentItem
    val archived = detail.owner.archived || detail.account.archived

    Card(
        modifier = Modifier.testTag("order-detail-payment-section"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "收款与欠款",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (balance.totalOutstandingCents == 0L) {
                    StatusTag(text = "已结清")
                }
            }
            if (balance.hosting) {
                LabeledValue(
                    label = "本期（第 ${((current?.periodIndex ?: 0) + 1)} 期）应收 / 已收 / 待收",
                    value = current?.let {
                        "${Money.formatCents(it.priceCents)} / ${Money.formatCents(it.paidCents)} / " +
                            Money.formatCents(it.outstandingCents)
                    } ?: "暂无期次",
                    modifier = Modifier.padding(top = 8.dp),
                )
                LabeledValue(
                    label = "历史欠款（往期未收）",
                    value = Money.formatCents(balance.historicalOutstandingCents),
                )
                LabeledValue(
                    label = "合计待收",
                    value = Money.formatCents(balance.totalOutstandingCents),
                )
            } else {
                val item = balance.items.firstOrNull()
                LabeledValue(
                    label = "约定总价 / 已收 / 待收",
                    value = item?.let {
                        "${Money.formatCents(it.priceCents)} / ${Money.formatCents(it.paidCents)} / " +
                            Money.formatCents(it.outstandingCents)
                    } ?: "未填写",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            LabeledValue(
                label = "收款方式",
                value = when (detail.order.paymentMode) {
                    PaymentMode.ONE_TIME -> "一次结清"
                    PaymentMode.INSTALLMENTS -> "分批收款"
                },
            )
            if (archived) {
                Text(
                    text = "老板或账号已归档：仍可结算欠款、作废或更正收款，但不会恢复任何任务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Button(
                onClick = { onRecordPayment(current?.periodId) },
                enabled = canPay && balance.receivableItems.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(if (balance.receivableItems.isEmpty()) "已全部结清" else "记录收款")
            }
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "收款记录（按期次）", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "共 ${detail.receipts.size} 笔，含已作废与已更正记录；分期收款不会自动抵扣其他期次。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (detail.receipts.isEmpty()) {
                Text(
                    text = "还没有收款记录。记录收款后，这里会显示实际时间、金额与渠道。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            balance.items.forEach { item ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.periodLabel,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (item.settled) {
                            "已结清"
                        } else {
                            "待收 ${Money.formatCents(item.outstandingCents)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val periodReceipts = detail.receiptsForPeriod(item.periodId)
                if (periodReceipts.isEmpty()) {
                    Text(
                        text = "该期暂无收款。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                periodReceipts.forEach { receipt ->
                    ReceiptRow(
                        receipt = receipt,
                        correctedToCents = detail.correctionFor(receipt.id)?.newAmountCents,
                        correctionReason = detail.correctionFor(receipt.id)?.reason,
                        canPay = canPay,
                        onAmend = { onAmendReceipt(receipt) },
                        onVoid = { onVoidReceipt(receipt) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReceiptRow(
    receipt: Receipt,
    correctedToCents: Long?,
    correctionReason: String?,
    canPay: Boolean,
    onAmend: () -> Unit,
    onVoid: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = Money.formatCents(receipt.amountCents),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            when (receipt.status) {
                ReceiptStatus.VOIDED -> StatusTag(text = "已作废")
                ReceiptStatus.REPLACED -> StatusTag(text = "已更正")
                ReceiptStatus.ACTIVE -> Unit
            }
        }
        Text(
            text = "${receipt.channel.label} · 实收时间 ${formatInstant(receipt.receivedAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                text = "已更正为 " + (correctedToCents?.let(Money::formatCents) ?: "另一笔记录") +
                    "，原金额仅保留为历史记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (receipt.status == ReceiptStatus.VOIDED) {
            Text(
                text = "该笔收款已作废，不计入实收与欠款余额。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!correctionReason.isNullOrBlank()) {
            Text(
                text = "更正原因：$correctionReason",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (receipt.effective && canPay) {
            Row {
                TextButton(onClick = onAmend) { Text("更正") }
                TextButton(onClick = onVoid) { Text("作废") }
            }
        }
    }
}

@Composable
internal fun NumericEditButton(
    initialValue: String,
    enabled: Boolean,
    onValue: (String) -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    TextButton(onClick = { show = true }, enabled = enabled) { Text("更新进度") }
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
private fun RenewalDialog(
    detail: OrderDetail,
    operationId: String,
    onDismiss: () -> Unit,
    onConfirm: (String, Int, String) -> Unit,
) {
    var duration by rememberSaveable { mutableStateOf("30") }
    var price by rememberSaveable {
        mutableStateOf(detail.currentPeriod?.priceCents?.let(Money::centsToYuanText) ?: "")
    }
    val days = duration.toIntOrNull()
    val start = detail.today
    val end = days?.takeIf { it > 0 }?.let { start.plusDays(it.toLong() - 1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("续期 · 原订单第 ${(detail.currentPeriod?.periodIndex ?: 0) + 2} 期") },
        text = {
            Column {
                Text(
                    text = "订单标识保持不变。新期从今天的游戏日期 ${formatGameDate(start)} 开始，" +
                        "不沿用旧到期日；旧期次、价格、打卡与截图全部保留。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "若当前时间早于每日刷新，今天的游戏日期仍是前一日；续期后只在该期开始日及之后生成任务，不会提前生成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                OutlinedTextField(
                    value = duration,
                    onValueChange = { duration = it },
                    label = { Text("续期天数") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Text(
                    text = if (end != null) {
                        "结束日期（含当天） ${formatGameDate(end)}"
                    } else {
                        "请输入有效的续期天数"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("本期价格（元）") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Text(
                    text = "新期次的实收从零开始，旧期次的价格与收款记录全部保留；" +
                        "若旧期仍有欠款，续期后仍可单独选择旧期次补收。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(operationId, days ?: 0, price) },
                enabled = days != null && days > 0,
            ) { Text("确认续期") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun taskSubtitle(task: OrderTask, windowLabel: String?, value: java.math.BigDecimal?): String {
    val parts = mutableListOf<String>()
    if (windowLabel != null) parts += "游戏周 $windowLabel"
    if (task.progressType == ProgressType.NUMERIC) {
        parts += "进度 ${(value ?: task.currentValue).toPlainString()}/${task.targetValue.toPlainString()}" +
            (task.unit?.let { " $it" } ?: "")
    }
    return parts.joinToString(" · ")
}

private fun numericSubtitle(task: OrderTask): String =
    if (task.progressType == ProgressType.NUMERIC) {
        "进度 ${task.currentValue.toPlainString()}/${task.targetValue.toPlainString()}" +
            (task.unit?.let { " $it" } ?: "")
    } else {
        ""
    }

private fun recurrenceText(task: OrderTask): String = when (task.recurrence) {
    com.daigan.record.data.model.RecurrenceType.ONE_TIME -> "一次性"
    com.daigan.record.data.model.RecurrenceType.DAILY -> "每日"
    com.daigan.record.data.model.RecurrenceType.WEEKLY -> "每周"
    com.daigan.record.data.model.RecurrenceType.SELECTED_WEEKDAYS ->
        "指定星期 " + task.weekdays.joinToString("、") { weekdayLabel(it) }
    com.daigan.record.data.model.RecurrenceType.EVERY_N_DAYS ->
        "每隔 ${task.everyNDays ?: "?"} 天"
}

private fun weekdayLabel(day: java.time.DayOfWeek): String = when (day) {
    java.time.DayOfWeek.MONDAY -> "周一"
    java.time.DayOfWeek.TUESDAY -> "周二"
    java.time.DayOfWeek.WEDNESDAY -> "周三"
    java.time.DayOfWeek.THURSDAY -> "周四"
    java.time.DayOfWeek.FRIDAY -> "周五"
    java.time.DayOfWeek.SATURDAY -> "周六"
    java.time.DayOfWeek.SUNDAY -> "周日"
}
