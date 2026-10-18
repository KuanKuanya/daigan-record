package com.daigan.record.feature.orders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daigan.record.core.time.GameRefreshRules
import com.daigan.record.core.time.HostingStatus
import com.daigan.record.data.model.OccurrenceRules
import com.daigan.record.data.model.OccurrenceSlot
import com.daigan.record.data.model.OrderDetail
import com.daigan.record.data.model.OrderTask
import com.daigan.record.data.model.TaskOccurrence
import com.daigan.record.data.repository.OrderRepository
import com.daigan.record.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class BackfillTarget(
    val task: OrderTask,
    val slot: OccurrenceSlot,
    val occurrence: TaskOccurrence?,
) {
    val completed: Boolean get() = occurrence?.completedAt != null
    val backfilled: Boolean get() = occurrence?.backfilled == true

    /** Stable identity of the exact occurrence the row represents. */
    val rowKey: String get() = "${task.id}|${slot.key}"
}

data class BackfillUiState(
    val loading: Boolean = true,
    val detail: OrderDetail? = null,
    val periodId: String = "",
    val selectedDate: LocalDate = LocalDate.now(),
    val firstSelectableDate: LocalDate = LocalDate.now(),
    val lastSelectableDate: LocalDate = LocalDate.now(),
    val targets: List<BackfillTarget> = emptyList(),
    val readOnly: Boolean = false,
    val readOnlyReason: String? = null,
    val error: String? = null,
)

@HiltViewModel
class BackfillViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val orderRepository: OrderRepository,
) : ViewModel() {

    private val orderId: String = checkNotNull(savedStateHandle[Routes.ORDER_ID_ARG]) {
        "orderId 参数缺失"
    }
    private val periodId: String = checkNotNull(savedStateHandle[Routes.PERIOD_ID_ARG]) {
        "periodId 参数缺失"
    }

    private val _uiState = MutableStateFlow(BackfillUiState(periodId = periodId))
    val uiState: StateFlow<BackfillUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private var initialisedDate = false

    init {
        viewModelScope.launch {
            orderRepository.observeOrderDetail(orderId)
                .catch { error ->
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        error = error.message ?: "读取订单失败",
                    )
                }
                .collect { detail ->
                    if (detail == null) {
                        _uiState.value = _uiState.value.copy(loading = false, error = "找不到该订单")
                        return@collect
                    }
                    val period = detail.periods.firstOrNull { it.id == periodId }
                    if (period == null) {
                        _uiState.value = _uiState.value.copy(
                            loading = false,
                            detail = detail,
                            error = "找不到该托管期次",
                        )
                        return@collect
                    }
                    // Selection can never move past the current game date or before
                    // the period start; not-yet-started periods stay read-only.
                    val first = period.startDate
                    val last = minOf(period.endDate, detail.today)
                    val date = if (!initialisedDate) {
                        initialisedDate = true
                        maxOf(first, minOf(detail.today.minusDays(1), last))
                    } else {
                        _uiState.value.selectedDate.coerceIn(first, maxOf(first, last))
                    }
                    _uiState.value = buildState(detail, period.id, date)
                }
        }
    }

    fun selectDate(date: LocalDate) {
        val detail = _uiState.value.detail ?: return
        val period = detail.periods.firstOrNull { it.id == periodId } ?: return
        if (detail.today.isBefore(period.startDate)) return
        val first = period.startDate
        val last = maxOf(first, minOf(period.endDate, detail.today))
        val clamped = date.coerceIn(first, last)
        _uiState.value = buildState(detail, periodId, clamped)
    }

    fun toggle(taskId: String, weeklyWindowStart: LocalDate?, completed: Boolean) {
        val date = _uiState.value.selectedDate
        viewModelScope.launch {
            runCatching {
                orderRepository.setHistoricalCompletion(
                    orderId = orderId,
                    periodId = periodId,
                    taskId = taskId,
                    date = date,
                    completed = completed,
                    weeklyWindowStart = weeklyWindowStart,
                )
            }.onSuccess {
                _messages.tryEmit(
                    if (completed) "已记录 $date 的完成状态" else "已清除 $date 的完成状态",
                )
            }.onFailure {
                _messages.tryEmit("补录失败：${it.message ?: "未知错误"}")
            }
        }
    }

    private fun buildState(detail: OrderDetail, periodId: String, date: LocalDate): BackfillUiState {
        val period = detail.periods.firstOrNull { it.id == periodId }
            ?: return _uiState.value.copy(loading = false, detail = detail, error = "找不到该托管期次")
        val rules = GameRefreshRules(
            zoneId = period.refresh.zoneId,
            dailyRefresh = period.refresh.dailyRefresh,
            weeklyRefreshDay = period.refresh.weeklyRefreshDay,
            weeklyRefreshTime = period.refresh.weeklyRefreshTime,
        )
        val first = period.startDate
        val last = maxOf(first, minOf(period.endDate, detail.today))
        val status = com.daigan.record.core.time.GameCalendar.hostingStatus(
            detail.today,
            period.startDate,
            period.endDate,
        )
        val readOnly = status == HostingStatus.NOT_STARTED
        val reason = when (status) {
            HostingStatus.NOT_STARTED -> "该托管期次尚未开始，不能提前打卡；开始后可在此补录或更正。"
            else -> null
        }
        if (readOnly) {
            return BackfillUiState(
                loading = false,
                detail = detail,
                periodId = periodId,
                selectedDate = first,
                firstSelectableDate = first,
                lastSelectableDate = first,
                targets = emptyList(),
                readOnly = true,
                readOnlyReason = reason,
            )
        }
        val occurrences = detail.occurrences
            .filter { occurrence -> detail.tasksForPeriod(periodId).any { it.id == occurrence.taskDefinitionId } }
            .associateBy { it.taskDefinitionId to it.occurrenceKey }
        val targets = detail.tasksForPeriod(periodId).flatMap { task ->
            // A weekly task can have two explicit windows on a day that contains the
            // weekly refresh. Both stay reachable and are identified by their key.
            OccurrenceRules.slotsForDate(
                recurrence = task.recurrence,
                weekdays = task.weekdays,
                everyNDays = task.everyNDays,
                periodStart = period.startDate,
                periodEnd = period.endDate,
                date = date,
                rules = rules,
            ).map { slot ->
                BackfillTarget(
                    task = task,
                    slot = slot,
                    occurrence = occurrences[task.id to slot.key],
                )
            }
        }
        return BackfillUiState(
            loading = false,
            detail = detail,
            periodId = periodId,
            selectedDate = date,
            firstSelectableDate = first,
            lastSelectableDate = last,
            targets = targets,
            readOnly = false,
            readOnlyReason = null,
            error = null,
        )
    }
}
