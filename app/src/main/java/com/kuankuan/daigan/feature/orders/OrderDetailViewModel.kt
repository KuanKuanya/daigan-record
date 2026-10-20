package com.kuankuan.daigan.feature.orders

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kuankuan.daigan.core.time.TimeProvider
import com.kuankuan.daigan.data.model.Attachment
import com.kuankuan.daigan.data.model.Money
import com.kuankuan.daigan.data.model.OrderDetail
import com.kuankuan.daigan.data.model.PaymentChannel
import com.kuankuan.daigan.data.model.PaymentRequest
import com.kuankuan.daigan.data.model.ReceiptCorrectionRequest
import com.kuankuan.daigan.data.model.RenewalResult
import com.kuankuan.daigan.data.model.UndoResult
import com.kuankuan.daigan.data.repository.AttachmentRepository
import com.kuankuan.daigan.data.repository.OrderRepository
import com.kuankuan.daigan.data.repository.ReceiptRepository
import com.kuankuan.daigan.data.repository.StaleOccurrenceException
import com.kuankuan.daigan.data.repository.DisplayedHostingSnapshot
import com.kuankuan.daigan.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OrderDetailUiState(
    val loading: Boolean = true,
    val detail: OrderDetail? = null,
    val error: String? = null,
    val lastBatchId: String? = null,
    val undoResult: UndoResult? = null,
    val paymentInFlight: Boolean = false,
    val maintenanceInFlight: Boolean = false,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class OrderDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val orderRepository: OrderRepository,
    private val attachmentRepository: AttachmentRepository,
    private val receiptRepository: ReceiptRepository,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val orderId: String = checkNotNull(savedStateHandle[Routes.ORDER_ID_ARG]) {
        "orderId 参数缺失"
    }

    private val _uiState = MutableStateFlow(OrderDetailUiState())
    val uiState: StateFlow<OrderDetailUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private val _renewalApplied = MutableSharedFlow<RenewalResult>(extraBufferCapacity = 1)
    val renewalApplied: SharedFlow<RenewalResult> = _renewalApplied

    /** Emits after a payment or receipt correction committed, so dialogs can close. */
    private val _financialApplied = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val financialApplied: SharedFlow<Unit> = _financialApplied

    private val _maintenanceApplied = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val maintenanceApplied: SharedFlow<Boolean> = _maintenanceApplied

    fun updateOrderDates(periodId: String?, start: java.time.LocalDate?, end: java.time.LocalDate?) =
        maintainOrder(false) { orderRepository.updateOrderDates(orderId, periodId, start, end) }

    fun deleteOrder() = maintainOrder(true) { orderRepository.deleteUnusedOrder(orderId) }
    fun cancelOrder() = maintainOrder(false) { orderRepository.cancelOrder(orderId) }

    private fun maintainOrder(deleted: Boolean, block: suspend () -> Unit) {
        if (_uiState.value.maintenanceInFlight) return
        _uiState.value = _uiState.value.copy(maintenanceInFlight = true)
        viewModelScope.launch {
            try {
                block()
                _maintenanceApplied.emit(deleted)
                emit(if (deleted) "已删除误建订单" else "订单已更新")
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                emit(error.message ?: "操作失败，请重试")
            } finally {
                _uiState.value = _uiState.value.copy(maintenanceInFlight = false)
            }
        }
    }

    private val refreshTick = MutableStateFlow(0L)
    // ViewModel actions run on Main; acquire before launch so queued taps cannot overlap.
    private var batchActionInFlight = false
    private var financialActionInFlight = false

    init {
        viewModelScope.launch {
            refreshTick
                .flatMapLatest { orderRepository.observeOrderDetail(orderId) }
                .map { detail ->
                    if (detail == null) {
                        OrderDetailUiState(loading = false, error = "找不到该订单")
                    } else {
                        _uiState.value.copy(loading = false, detail = detail, error = null)
                    }
                }
                .catch { error ->
                    emit(OrderDetailUiState(loading = false, error = error.message ?: "读取订单失败"))
                }
                .collect { state -> _uiState.value = state }
        }
    }

    /** Recomputes the current game day/window when returning to the foreground. */
    fun refresh() {
        refreshTick.value += 1
    }

    fun toggleShortTask(taskId: String, completed: Boolean) {
        runWrite {
            orderRepository.setShortTaskCompleted(taskId, completed)
        }
    }

    fun updateShortProgress(taskId: String, text: String) {
        val value = text.trim().toBigDecimalOrNull()
        if (value == null) {
            emit("请输入有效的数值")
            return
        }
        runWrite {
            orderRepository.updateShortTaskProgress(taskId, value)
        }
    }

    fun completeShortOrder() {
        runBatch {
            orderRepository.completeShortOrder(orderId, UUID.randomUUID().toString())
        }
    }

    fun setCalendarCompletion(
        periodId: String,
        taskId: String,
        date: java.time.LocalDate,
        occurrenceKey: String,
        completed: Boolean,
    ) {
        runWrite {
            orderRepository.setHistoricalCompletion(
                orderId = orderId,
                periodId = periodId,
                taskId = taskId,
                date = date,
                completed = completed,
                weeklyWindowStart = if (occurrenceKey.startsWith("W:")) {
                    java.time.LocalDate.parse(occurrenceKey.substringAfter(":"))
                } else null,
            )
        }
    }

    fun toggleOccurrence(taskId: String, occurrenceKey: String, completed: Boolean) {
        runWrite {
            orderRepository.setOccurrenceCompleted(taskId, completed, occurrenceKey)
        }
    }

    fun updateOccurrenceProgress(taskId: String, occurrenceKey: String, text: String) {
        val value = text.trim().toBigDecimalOrNull()
        if (value == null) {
            emit("请输入有效的数值")
            return
        }
        runWrite {
            orderRepository.updateOccurrenceProgress(taskId, value, occurrenceKey)
        }
    }

    fun completeHostingOrder() {
        val operationId = UUID.randomUUID().toString()
        val displayed = _uiState.value.detail?.toDisplayedSnapshot()
        launchBatchAction {
            runCatching {
                val snapshot = displayed
                    ?: throw StaleOccurrenceException("展示数据已过期，请刷新后再试")
                orderRepository.completeHostingOrder(orderId, operationId, snapshot)
            }
                .onSuccess { result ->
                    if (result.completedCount > 0) {
                        _uiState.value = _uiState.value.copy(lastBatchId = result.batchId)
                    }
                    emit(
                        if (result.alreadyApplied) {
                            "该批量操作已经生效"
                        } else {
                            "已完成 ${result.completedCount} 项" +
                                if (result.skippedCount > 0) "，跳过 ${result.skippedCount} 项" else ""
                        },
                    )
                }
                .onFailure { error ->
                    if (error is StaleOccurrenceException) {
                        refresh()
                        emit("已跨过刷新时间，详情已刷新；请按新的游戏日/游戏周重新确认")
                    } else {
                        emit("完成失败：${error.message ?: "未知错误"}")
                    }
                }
        }
    }

    fun undoLastBatch() {
        val batchId = _uiState.value.lastBatchId ?: return
        launchBatchAction {
            runCatching { orderRepository.undoBatch(batchId) }
                .onSuccess { result ->
                    _uiState.value = _uiState.value.copy(
                        lastBatchId = null,
                        undoResult = result,
                    )
                }
                .onFailure { emit("撤销失败：${it.message ?: "未知错误"}") }
        }
    }

    fun clearUndoResult() {
        _uiState.value = _uiState.value.copy(undoResult = null)
    }

    fun addAttachment(taskDefinitionId: String?, occurrenceId: String?, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                attachmentRepository.addAttachment(taskDefinitionId, occurrenceId, uri)
            }.onSuccess { emit("已添加截图") }
                .onFailure { emit("添加截图失败：${it.message ?: "无法读取图片"}") }
        }
    }

    fun replaceAttachment(attachmentId: String, uri: Uri) {
        viewModelScope.launch {
            runCatching { attachmentRepository.replaceAttachment(attachmentId, uri) }
                .onSuccess { emit("已替换截图") }
                .onFailure { emit("替换截图失败，原截图已保留：${it.message ?: "无法读取图片"}") }
        }
    }

    fun deleteAttachment(attachmentId: String) {
        viewModelScope.launch {
            runCatching { attachmentRepository.deleteAttachment(attachmentId) }
                .onSuccess { emit("已删除截图") }
                .onFailure { emit("删除截图失败：${it.message ?: "未知错误"}") }
        }
    }

    fun fileFor(attachment: Attachment): File = attachmentRepository.fileFor(attachment)

    fun renew(operationId: String, durationDays: Int, priceYuan: String) {
        val cents = com.kuankuan.daigan.data.model.Money.parseYuanToCentsOrNull(priceYuan)
        if (cents == null) {
            emit("续期价格需为不超过两位小数的非负数字")
            return
        }
        if (durationDays <= 0) {
            emit("续期天数必须大于 0")
            return
        }
        viewModelScope.launch {
            runCatching {
                orderRepository.renewHosting(orderId, operationId, durationDays, cents)
            }.onSuccess { result ->
                _uiState.value = _uiState.value.copy(lastBatchId = null)
                _messages.emit(
                    if (result.alreadyApplied) {
                        "该续期请求已经生效，未重复创建期次"
                    } else {
                        "已续期：第 ${result.periodIndex + 1} 期，${result.startDate} 至 ${result.endDate}"
                    },
                )
                _renewalApplied.emit(result)
            }.onFailure { emit("续期失败：${it.message ?: "未知错误"}") }
        }
    }

    /** Fresh clock reading for a payment form default. */
    fun now(): Instant = timeProvider.now()

    /**
     * Records a payment against an explicitly chosen period (or the short order).
     * The gate is acquired synchronously before the coroutine starts, so two taps in
     * the same frame cannot both reach the repository. [operationId] comes from the
     * dialog and is kept for retries of the same submission.
     */
    fun recordPayment(
        periodId: String?,
        amountYuan: String,
        receivedAt: Instant,
        channel: PaymentChannel,
        note: String,
        operationId: String,
    ) {
        val cents = Money.parsePositiveYuanToCentsOrNull(amountYuan)
        if (cents == null) {
            emit("请输入大于 0 且最多两位小数的收款金额")
            return
        }
        if (operationId.isBlank()) {
            emit("缺少收款操作标识，请重新打开收款窗口")
            return
        }
        runFinancial {
            runCatching {
                receiptRepository.recordPayment(
                    PaymentRequest(
                        orderId = orderId,
                        periodId = periodId,
                        amountCents = cents,
                        receivedAt = receivedAt,
                        channel = channel,
                        note = note,
                        operationId = operationId,
                    ),
                )
            }.onSuccess { result ->
                emit(
                    if (result.alreadyApplied) {
                        "该笔收款已经记录，未重复入账"
                    } else {
                        "已记录实收 ${Money.formatCents(result.receipt.amountCents)}（${result.receipt.channel.label}）"
                    },
                )
                _financialApplied.tryEmit(Unit)
            }.onFailure { error ->
                emit("收款失败：${error.message ?: "未知错误"}")
            }
        }
    }

    fun amendReceipt(
        receiptId: String,
        amountYuan: String,
        receivedAt: Instant,
        channel: PaymentChannel,
        note: String,
        reason: String,
        operationId: String,
    ) {
        val cents = Money.parsePositiveYuanToCentsOrNull(amountYuan)
        if (cents == null) {
            emit("请输入大于 0 且最多两位小数的更正金额")
            return
        }
        if (operationId.isBlank()) {
            emit("缺少更正操作标识，请重新打开更正窗口")
            return
        }
        runFinancial {
            runCatching {
                receiptRepository.correctReceipt(
                    ReceiptCorrectionRequest.Amend(
                        operationId = operationId,
                        receiptId = receiptId,
                        amountCents = cents,
                        receivedAt = receivedAt,
                        channel = channel,
                        note = note,
                        reason = reason,
                    ),
                )
            }.onSuccess { result ->
                emit(
                    if (result.alreadyApplied) {
                        "该更正已经生效，未重复处理"
                    } else {
                        "已更正收款，原记录保留在流水中"
                    },
                )
                _financialApplied.tryEmit(Unit)
            }.onFailure { error ->
                emit("更正失败：${error.message ?: "未知错误"}")
            }
        }
    }

    fun voidReceipt(receiptId: String, reason: String, operationId: String) {
        if (operationId.isBlank()) {
            emit("缺少作废操作标识，请重新打开作废窗口")
            return
        }
        runFinancial {
            runCatching {
                receiptRepository.correctReceipt(
                    ReceiptCorrectionRequest.Void(
                        operationId = operationId,
                        receiptId = receiptId,
                        reason = reason,
                    ),
                )
            }.onSuccess { result ->
                emit(if (result.alreadyApplied) "该收款已经作废" else "已作废该笔收款，记录仍保留在流水中")
                _financialApplied.tryEmit(Unit)
            }.onFailure { error ->
                emit("作废失败：${error.message ?: "未知错误"}")
            }
        }
    }

    private fun runFinancial(block: suspend () -> Unit) {
        if (financialActionInFlight) return
        financialActionInFlight = true
        _uiState.value = _uiState.value.copy(paymentInFlight = true)
        viewModelScope.launch {
            try {
                block()
            } finally {
                financialActionInFlight = false
                _uiState.value = _uiState.value.copy(paymentInFlight = false)
            }
        }
    }

    /** Serialize completion and undo; a no-op must retain the last useful undo target. */
    private fun launchBatchAction(block: suspend () -> Unit) {
        if (batchActionInFlight) return
        batchActionInFlight = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                batchActionInFlight = false
            }
        }
    }

    private fun runWrite(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { error ->
                    if (error is StaleOccurrenceException) {
                        refresh()
                        emit("已跨过刷新时间，详情已刷新；请按新的游戏日/游戏周重新确认")
                    } else {
                        emit(error.message ?: "操作失败，请重试")
                    }
                }
        }
    }

    private fun runBatch(block: suspend () -> com.kuankuan.daigan.data.model.BulkCompletionResult) {
        launchBatchAction {
            runCatching { block() }
                .onSuccess { result ->
                    if (result.completedCount > 0) {
                        _uiState.value = _uiState.value.copy(lastBatchId = result.batchId)
                    }
                    emit(
                        if (result.alreadyApplied) {
                            "该批量操作已经生效"
                        } else {
                            "已完成 ${result.completedCount} 项" +
                                if (result.skippedCount > 0) "，跳过 ${result.skippedCount} 项" else ""
                        },
                    )
                }
                .onFailure { emit("完成失败：${it.message ?: "未知错误"}") }
        }
    }

    private fun emit(message: String) {
        _messages.tryEmit(message)
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? =
        runCatching { BigDecimal(trim()) }.getOrNull()
}

/**
 * Captures what the order-detail page is showing for the current hosting period: the
 * period identity, the game date and each task's occurrence window. Validated inside
 * the bulk-completion transaction so a crossed refresh boundary cannot complete a
 * different day's tasks.
 */
fun OrderDetail.toDisplayedSnapshot(): DisplayedHostingSnapshot? {
    val period = currentPeriod ?: return null
    return DisplayedHostingSnapshot(
        orderId = order.id,
        periodId = period.id,
        gameDate = today,
        occurrenceKeys = currentHostingTasks().associate { it.task.id to it.slot.key },
    )
}
