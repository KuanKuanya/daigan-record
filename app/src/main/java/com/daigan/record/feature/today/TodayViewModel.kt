package com.daigan.record.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.model.ShortWorkOrder
import com.daigan.record.data.model.TodayHostingOrder
import com.daigan.record.data.model.UndoResult
import com.daigan.record.data.repository.OrderRepository
import com.daigan.record.data.repository.StaleOccurrenceException
import com.daigan.record.data.repository.toDisplayedSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class TodayTab(val label: String) {
    HOSTING("托管打卡"),
    SHORT_TASK("短期任务"),
}

data class TodayUiState(
    val dateLabel: String = "",
    val selectedTab: TodayTab = TodayTab.HOSTING,
    val hosting: List<TodayHostingOrder> = emptyList(),
    val shortOrders: List<ShortWorkOrder> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val lastBatchId: String? = null,
    val undoResult: UndoResult? = null,
    val completedExpanded: Boolean = false,
) {
    val activeHosting: List<TodayHostingOrder>
        get() = hosting.filter { !it.allTasksCompleted || it.expired }

    val completedHosting: List<TodayHostingOrder>
        get() = hosting.filter { it.allTasksCompleted && !it.expired }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val timeProvider: TimeProvider,
    private val orderRepository: OrderRepository,
) : ViewModel() {

    private val selectedTab = MutableStateFlow(TodayTab.HOSTING)
    private val refreshTick = MutableStateFlow(0L)
    // ViewModel actions run on Main; acquire before launch so queued taps cannot overlap.
    private var batchActionInFlight = false
    private val lastBatchId = MutableStateFlow<String?>(null)
    private val undoResult = MutableStateFlow<UndoResult?>(null)
    private val completedExpanded = MutableStateFlow(false)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private val work: kotlinx.coroutines.flow.Flow<Pair<List<TodayHostingOrder>, List<ShortWorkOrder>>> =
        refreshTick.flatMapLatest {
            combine(
                orderRepository.observeTodayHosting(),
                orderRepository.observeTodayShort(),
            ) { hosting, short -> hosting to short }
        }

    val uiState: StateFlow<TodayUiState> = combine(
        selectedTab,
        work,
        lastBatchId,
        undoResult,
        completedExpanded,
    ) { tab, (hosting, shortOrders), batchId, undo, expanded ->
        TodayUiState(
            dateLabel = dateLabel(),
            selectedTab = tab,
            hosting = hosting,
            shortOrders = shortOrders,
            loading = false,
            lastBatchId = batchId,
            undoResult = undo,
            completedExpanded = expanded,
        )
    }
        .catch { error ->
            emit(
                TodayUiState(
                    dateLabel = dateLabel(),
                    loading = false,
                    error = error.message ?: "读取今日事项失败",
                ),
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TodayUiState(dateLabel = dateLabel(), selectedTab = TodayTab.HOSTING),
        )

    fun selectTab(tab: TodayTab) {
        selectedTab.value = tab
    }

    /** Recomputes game dates on resume so a day/window boundary is not stale. */
    fun refresh() {
        refreshTick.value += 1
    }

    fun toggleCompletedSection() {
        completedExpanded.value = !completedExpanded.value
    }

    fun completeAllHosting() {
        val operationId = UUID.randomUUID().toString()
        val displayed = uiState.value.hosting.map { it.toDisplayedSnapshot() }
        launchBatchAction {
            runCatching { orderRepository.completeAllHosting(operationId, displayed) }
                .onSuccess { result ->
                    if (result.completedCount > 0) lastBatchId.value = result.batchId
                    _messages.tryEmit(
                        if (result.alreadyApplied) {
                            "该批量操作已经生效"
                        } else {
                            "已完成 ${result.completedCount} 项托管任务"
                        },
                    )
                }
                .onFailure { refreshAfterStale(it, "批量完成失败") }
        }
    }

    fun completeHostingOrder(orderId: String) {
        val operationId = UUID.randomUUID().toString()
        val displayed = uiState.value.hosting
            .firstOrNull { it.order.id == orderId }
            ?.toDisplayedSnapshot()
        launchBatchAction {
            runCatching {
                val snapshot = displayed
                    ?: throw StaleOccurrenceException("展示数据已过期，请刷新后再试")
                orderRepository.completeHostingOrder(orderId, operationId, snapshot)
            }
                .onSuccess { result ->
                    if (result.completedCount > 0) lastBatchId.value = result.batchId
                    _messages.tryEmit("该单已完成 ${result.completedCount} 项")
                }
                .onFailure { refreshAfterStale(it, "整单完成失败") }
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

    /**
     * A stale bulk submission means a refresh boundary was crossed between render and
     * tap. Refresh and explain rather than silently completing another game day.
     */
    private fun refreshAfterStale(error: Throwable, prefix: String) {
        if (error is StaleOccurrenceException) {
            refresh()
            _messages.tryEmit("已跨过刷新时间，列表已刷新，请确认新的游戏日/游戏周后再操作")
        } else {
            _messages.tryEmit("$prefix：${error.message ?: "未知错误"}")
        }
    }

    fun toggleHostingTask(taskId: String, occurrenceKey: String, completed: Boolean) {
        viewModelScope.launch {
            runCatching {
                orderRepository.setOccurrenceCompleted(taskId, completed, occurrenceKey)
            }.onFailure { error ->
                if (error is StaleOccurrenceException) {
                    refresh()
                    _messages.tryEmit("已跨过刷新时间，列表已刷新，请确认新的游戏日/游戏周后再操作")
                } else {
                    _messages.tryEmit("更新打卡失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    fun updateHostingProgress(taskId: String, occurrenceKey: String, text: String) {
        val value = text.trim().toBigDecimalOrNull()
        if (value == null) {
            _messages.tryEmit("请输入有效的数值")
            return
        }
        viewModelScope.launch {
            runCatching {
                orderRepository.updateOccurrenceProgress(taskId, value, occurrenceKey)
            }.onFailure { error ->
                if (error is StaleOccurrenceException) {
                    refresh()
                    _messages.tryEmit("已跨过刷新时间，列表已刷新，请确认新的游戏日/游戏周后再操作")
                } else {
                    _messages.tryEmit("更新进度失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    fun toggleShortTask(taskId: String, completed: Boolean) {
        viewModelScope.launch {
            runCatching { orderRepository.setShortTaskCompleted(taskId, completed) }
                .onFailure { _messages.tryEmit("更新任务失败：${it.message ?: "未知错误"}") }
        }
    }

    fun updateShortProgress(taskId: String, text: String) {
        val value = text.trim().toBigDecimalOrNull()
        if (value == null) {
            _messages.tryEmit("请输入有效的数值")
            return
        }
        viewModelScope.launch {
            runCatching { orderRepository.updateShortTaskProgress(taskId, value) }
                .onFailure { _messages.tryEmit("更新进度失败：${it.message ?: "未知错误"}") }
        }
    }

    fun undoLastBatch() {
        val batchId = lastBatchId.value ?: return
        launchBatchAction {
            runCatching { orderRepository.undoBatch(batchId) }
                .onSuccess { result ->
                    lastBatchId.value = null
                    undoResult.value = result
                }
                .onFailure { _messages.tryEmit("撤销失败：${it.message ?: "未知错误"}") }
        }
    }

    fun clearUndoResult() {
        undoResult.value = null
    }

    private fun dateLabel(): String =
        DateTimeFormatter
            .ofPattern("M 月 d 日 · EEEE", Locale.SIMPLIFIED_CHINESE)
            .format(timeProvider.now().atZone(ZoneId.systemDefault()))

    private fun String.toBigDecimalOrNull(): BigDecimal? =
        runCatching { BigDecimal(trim()) }.getOrNull()
}
