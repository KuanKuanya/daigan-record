package com.daigan.record.feature.orders

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daigan.record.data.model.Attachment
import com.daigan.record.data.model.OrderDetail
import com.daigan.record.data.model.RenewalResult
import com.daigan.record.data.model.UndoResult
import com.daigan.record.data.repository.AttachmentRepository
import com.daigan.record.data.repository.OrderRepository
import com.daigan.record.data.repository.StaleOccurrenceException
import com.daigan.record.data.repository.DisplayedHostingSnapshot
import com.daigan.record.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.math.BigDecimal
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
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class OrderDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val orderRepository: OrderRepository,
    private val attachmentRepository: AttachmentRepository,
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

    private val refreshTick = MutableStateFlow(0L)

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
        viewModelScope.launch {
            runCatching {
                val snapshot = displayed
                    ?: throw StaleOccurrenceException("展示数据已过期，请刷新后再试")
                orderRepository.completeHostingOrder(orderId, operationId, snapshot)
            }
                .onSuccess { result ->
                    _uiState.value = _uiState.value.copy(lastBatchId = result.batchId)
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
        viewModelScope.launch {
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
        val cents = com.daigan.record.data.model.Money.parseYuanToCentsOrNull(priceYuan)
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

    private fun runBatch(block: suspend () -> com.daigan.record.data.model.BulkCompletionResult) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { result ->
                    _uiState.value = _uiState.value.copy(lastBatchId = result.batchId)
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
