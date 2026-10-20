package com.kuankuan.daigan.feature.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kuankuan.daigan.data.model.Money
import com.kuankuan.daigan.data.model.OrderBalance
import com.kuankuan.daigan.data.model.PaymentChannel
import com.kuankuan.daigan.data.model.PaymentMode
import com.kuankuan.daigan.data.model.PaymentRequest
import com.kuankuan.daigan.data.model.PayableBalance
import com.kuankuan.daigan.data.model.Receipt
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Payment entry. The user must pick the hosting period explicitly; the dialog preselects
 * the current period (never the oldest debt) and always shows owner/account/order plus
 * the selected balance before confirming.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PaymentDialog(
    orderTitle: String,
    ownerLabel: String,
    accountLabel: String,
    balance: OrderBalance,
    initialPeriodId: String?,
    now: Instant,
    operationId: String,
    inFlight: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (PaymentRequest) -> Unit,
) {
    val receivable = balance.receivableItems
    var selectedPeriodId by rememberSaveable {
        mutableStateOf(
            receivable.firstOrNull { it.periodId == initialPeriodId }?.periodId
                ?: receivable.firstOrNull()?.periodId,
        )
    }
    val selected: PayableBalance? = if (balance.hosting) {
        receivable.firstOrNull { it.periodId == selectedPeriodId }
    } else {
        receivable.firstOrNull()
    }
    val oneTime = balance.paymentMode == PaymentMode.ONE_TIME

    var amount by rememberSaveable(selected?.periodId) {
        mutableStateOf(selected?.outstandingCents?.let(Money::centsToYuanText) ?: "")
    }
    var receivedAtMillis by rememberSaveable { mutableStateOf(now.toEpochMilli()) }
    var channel by rememberSaveable { mutableStateOf(PaymentChannel.WECHAT) }
    var note by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!inFlight) onDismiss() },
        title = { Text("记录收款") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = orderTitle, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "$ownerLabel · $accountLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (selected == null) {
                    Text(
                        text = "该订单已全部结清，没有待收款项。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    if (balance.hosting) {
                        Text(
                            text = "归属期次（请手动选择，不会自动抵扣最早欠款）",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        receivable.forEach { item ->
                            FilterChip(
                                selected = selected.periodId == item.periodId,
                                onClick = { selectedPeriodId = item.periodId },
                                label = {
                                    Text(
                                        "${item.periodLabel} · 待收 ${Money.formatCents(item.outstandingCents)}",
                                    )
                                },
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = "${selected.periodLabel} · 应收 ${Money.formatCents(selected.priceCents)}" +
                            " · 已收 ${Money.formatCents(selected.paidCents)}" +
                            " · 待收 ${Money.formatCents(selected.outstandingCents)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )

                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { Text(if (oneTime) "本次实收（一次结清需全额）" else "本次实收金额（元）") },
                        readOnly = oneTime,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                    Text(
                        text = if (oneTime) {
                            "该订单为一次结清：本次将收取全部剩余 ${Money.formatCents(selected.outstandingCents)}。"
                        } else {
                            "可分批收款，本次最多 ${Money.formatCents(selected.outstandingCents)}。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    InstantFields(
                        instant = Instant.ofEpochMilli(receivedAtMillis),
                        onInstant = { receivedAtMillis = it.toEpochMilli() },
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        PaymentChannel.entries.forEach { entry ->
                            FilterChip(
                                selected = channel == entry,
                                onClick = { channel = entry },
                                label = { Text(entry.label) },
                            )
                        }
                    }
                    if (channel == PaymentChannel.OTHER) {
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it },
                            label = { Text("备注（选填）") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                        )
                    }
                }
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null && !inFlight,
                onClick = {
                    if (selected == null) return@TextButton
                    val cents = if (oneTime) {
                        selected.outstandingCents
                    } else {
                        Money.parsePositiveYuanToCentsOrNull(amount)
                    }
                    when {
                        cents == null -> error = "请输入大于 0 且最多两位小数的金额"
                        cents > selected.outstandingCents ->
                            error = "金额不能超过待收 ${Money.formatCents(selected.outstandingCents)}"
                        oneTime && cents != selected.outstandingCents ->
                            error = "一次结清需收齐全部剩余金额"
                        else -> {
                            error = null
                            onConfirm(
                                PaymentRequest(
                                    orderId = balance.orderId,
                                    periodId = selected.periodId,
                                    amountCents = cents,
                                    receivedAt = Instant.ofEpochMilli(receivedAtMillis),
                                    channel = channel,
                                    note = note,
                                    operationId = operationId,
                                ),
                            )
                        }
                    }
                },
            ) { Text(if (inFlight) "正在记录…" else "确认记录") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inFlight) { Text("取消") }
        },
    )
}

/** Amend an existing receipt. The original row stays visible with a 已更正 badge. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AmendReceiptDialog(
    receipt: Receipt,
    periodLabel: String,
    inFlight: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (
        amountYuan: String,
        receivedAt: Instant,
        channel: PaymentChannel,
        note: String,
        reason: String,
    ) -> Unit,
) {
    var amount by rememberSaveable { mutableStateOf(Money.centsToYuanText(receipt.amountCents)) }
    var receivedAtMillis by rememberSaveable { mutableStateOf(receipt.receivedAt.toEpochMilli()) }
    var channel by rememberSaveable { mutableStateOf(receipt.channel) }
    var note by rememberSaveable { mutableStateOf(receipt.note.orEmpty()) }
    var reason by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!inFlight) onDismiss() },
        title = { Text("更正收款") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "$periodLabel · 原金额 ${Money.formatCents(receipt.amountCents)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "更正不会删除原记录：原流水会标记“已更正”，新金额作为独立生效记录保存。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("更正后金额（元）") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                InstantFields(
                    instant = Instant.ofEpochMilli(receivedAtMillis),
                    onInstant = { receivedAtMillis = it.toEpochMilli() },
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    PaymentChannel.entries.forEach { entry ->
                        FilterChip(
                            selected = channel == entry,
                            onClick = { channel = entry },
                            label = { Text(entry.label) },
                        )
                    }
                }
                if (channel == PaymentChannel.OTHER) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("备注（选填）") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("更正原因（选填，写入审计记录）") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !inFlight,
                onClick = {
                    val cents = Money.parsePositiveYuanToCentsOrNull(amount)
                    if (cents == null) {
                        error = "请输入大于 0 且最多两位小数的金额"
                        return@TextButton
                    }
                    error = null
                    onConfirm(
                        amount,
                        Instant.ofEpochMilli(receivedAtMillis),
                        channel,
                        note,
                        reason,
                    )
                },
            ) { Text(if (inFlight) "正在保存…" else "确认更正") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inFlight) { Text("取消") }
        },
    )
}

@Composable
internal fun VoidReceiptDialog(
    receipt: Receipt,
    periodLabel: String,
    inFlight: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (reason: String) -> Unit,
) {
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!inFlight) onDismiss() },
        title = { Text("作废这笔收款？") },
        text = {
            Column {
                Text(
                    text = "$periodLabel · ${Money.formatCents(receipt.amountCents)} · ${receipt.channel.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "作废后待收金额会重新计算，原流水仍保留在收款明细中并标记“已作废”。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("作废原因（选填，写入审计记录）") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason) }, enabled = !inFlight) {
                Text(if (inFlight) "正在作废…" else "确认作废")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inFlight) { Text("取消") }
        },
    )
}

/** Shared actual-receipt date/time pickers (payment, amendment and order creation). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InstantFields(
    instant: Instant,
    onInstant: (Instant) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val local = instant.atZone(zone)
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            ReadOnlyField(
                label = "收款日期",
                value = dateFormatter.format(local.toLocalDate()),
                onClick = { showDate = true },
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            ReadOnlyField(
                label = "收款时间",
                value = timeFormatter.format(local.toLocalTime()),
                onClick = { showTime = true },
            )
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = local.toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { millis ->
                            val date = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC).toLocalDate()
                            onInstant(
                                date.atTime(local.toLocalTime()).atZone(zone).toInstant(),
                            )
                        }
                        showDate = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) { Text("取消") }
            },
        ) { DatePicker(state = state) }
    }

    if (showTime) {
        val state = rememberTimePickerState(
            initialHour = local.hour,
            initialMinute = local.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text("收款时间") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val time = LocalTime.of(state.hour, state.minute)
                        onInstant(local.toLocalDate().atTime(time).atZone(zone).toInstant())
                        showTime = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTime = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ReadOnlyField(label: String, value: String, onClick: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        trailingIcon = { TextButton(onClick = onClick) { Text("选择") } },
        modifier = Modifier.fillMaxWidth(),
    )
}

