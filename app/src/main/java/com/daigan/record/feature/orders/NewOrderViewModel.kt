package com.daigan.record.feature.orders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daigan.record.core.time.GameCalendar
import com.daigan.record.core.time.GameRefreshRules
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.model.GameAccount
import com.daigan.record.data.model.HostingOrderDraft
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.Owner
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.ShortOrderDraft
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.data.repository.GameAccountRepository
import com.daigan.record.data.repository.OrderRepository
import com.daigan.record.data.repository.OwnerRepository
import com.daigan.record.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewOrderUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val owner: Owner? = null,
    val account: GameAccount? = null,
    val type: OrderType = OrderType.HOSTING,
    val title: String = "日常托管",
    val priceYuan: String = "",
    val paymentMode: PaymentMode = PaymentMode.ONE_TIME,
    val deadlineDate: LocalDate? = null,
    val notes: String = "",
    val startDate: LocalDate = LocalDate.now(),
    val endDate: LocalDate = LocalDate.now().plusDays(29),
    val durationText: String = "30",
    val zoneText: String = ZoneId.systemDefault().id,
    val dailyRefresh: LocalTime = LocalTime.of(4, 0),
    val weeklyRefreshDay: DayOfWeek = DayOfWeek.MONDAY,
    val weeklyRefreshTime: LocalTime = LocalTime.of(5, 0),
    val tasks: List<TaskDraft> = listOf(TaskDraft()),
    val formErrors: Map<String, String> = emptyMap(),
    val saveError: String? = null,
    val saving: Boolean = false,
) {
    val errorMessages: List<String> get() = formErrors.values.distinct()
    val isShort: Boolean get() = type == OrderType.SHORT_TASK
}

@HiltViewModel
class NewOrderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val ownerRepository: OwnerRepository,
    private val accountRepository: GameAccountRepository,
    private val orderRepository: OrderRepository,
    timeProvider: TimeProvider,
) : ViewModel() {

    private val ownerId: String = checkNotNull(savedStateHandle[Routes.OWNER_ID_ARG]) {
        "ownerId 参数缺失"
    }
    private val accountId: String = checkNotNull(savedStateHandle[Routes.ACCOUNT_ID_ARG]) {
        "accountId 参数缺失"
    }

    private val initialToday = GameCalendar.gameDate(
        timeProvider.now(),
        GameRefreshRules.provisional(ZoneId.systemDefault()),
    )

    private val _uiState = MutableStateFlow(
        NewOrderUiState(
            startDate = initialToday,
            endDate = initialToday.plusDays(29),
            zoneText = ZoneId.systemDefault().id,
        ),
    )
    val uiState: StateFlow<NewOrderUiState> = _uiState.asStateFlow()

    private val _createdOrderId = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val createdOrderId: SharedFlow<String> = _createdOrderId

    init {
        viewModelScope.launch {
            val owner = ownerRepository.findOwner(ownerId)
            val account = accountRepository.findAccount(accountId)
            val error = when {
                owner == null -> "找不到这位老板"
                account == null -> "找不到这个游戏账号"
                account.ownerId != ownerId -> "游戏账号不属于该老板"
                owner.archived -> "老板已归档，请先恢复老板后再接新单"
                account.archived -> "游戏账号已归档，请先恢复账号后再接新单"
                else -> null
            }
            _uiState.update {
                it.copy(
                    loading = false,
                    loadError = error,
                    owner = owner,
                    account = account,
                )
            }
        }
    }

    fun selectType(type: OrderType) {
        _uiState.update { state ->
            val defaultHosting = state.title == "日常托管"
            val defaultShort = state.title == "短期任务"
            state.copy(
                type = type,
                title = when {
                    type == OrderType.HOSTING && defaultShort -> "日常托管"
                    type == OrderType.SHORT_TASK && defaultHosting -> "短期任务"
                    else -> state.title
                },
                formErrors = emptyMap(),
                saveError = null,
            )
        }
    }

    fun updateTitle(value: String) = _uiState.update { it.copy(title = value) }

    fun updatePrice(value: String) = _uiState.update { it.copy(priceYuan = value) }

    fun selectPaymentMode(mode: PaymentMode) = _uiState.update { it.copy(paymentMode = mode) }

    fun updateNotes(value: String) = _uiState.update { it.copy(notes = value) }

    fun setDeadline(date: LocalDate?) = _uiState.update { it.copy(deadlineDate = date) }

    fun setStartDate(date: LocalDate) {
        _uiState.update { state ->
            val duration = state.durationText.toIntOrNull()?.takeIf { it > 0 } ?: 1
            state.copy(startDate = date, endDate = date.plusDays(duration.toLong() - 1))
        }
    }

    fun setDurationText(value: String) {
        _uiState.update { state ->
            val duration = value.toIntOrNull()
            state.copy(
                durationText = value,
                endDate = if (duration != null && duration > 0) {
                    state.startDate.plusDays(duration.toLong() - 1)
                } else {
                    state.endDate
                },
            )
        }
    }

    fun setEndDate(date: LocalDate) {
        _uiState.update { state ->
            val duration = if (date.isBefore(state.startDate)) {
                state.durationText
            } else {
                (date.toEpochDay() - state.startDate.toEpochDay() + 1).toString()
            }
            state.copy(endDate = date, durationText = duration)
        }
    }

    fun setZoneText(value: String) = _uiState.update { it.copy(zoneText = value) }

    fun setDailyRefresh(time: LocalTime) = _uiState.update { it.copy(dailyRefresh = time) }

    fun setWeeklyRefreshDay(day: DayOfWeek) = _uiState.update { it.copy(weeklyRefreshDay = day) }

    fun setWeeklyRefreshTime(time: LocalTime) = _uiState.update { it.copy(weeklyRefreshTime = time) }

    fun addTask() = _uiState.update { it.copy(tasks = it.tasks + TaskDraft()) }

    fun removeTask(index: Int) = _uiState.update { state ->
        if (state.tasks.size <= 1) state else state.copy(tasks = state.tasks.filterIndexed { i, _ -> i != index })
    }

    fun updateTask(index: Int, transform: (TaskDraft) -> TaskDraft) {
        _uiState.update { state ->
            state.copy(
                tasks = state.tasks.mapIndexed { i, task -> if (i == index) transform(task) else task },
            )
        }
    }

    fun submit() {
        val state = _uiState.value
        if (state.saving) return
        if (state.loadError != null) {
            _uiState.update { it.copy(saveError = state.loadError) }
            return
        }
        val zone = runCatching { ZoneId.of(state.zoneText.trim()) }.getOrNull()
        if (zone == null) {
            _uiState.update { it.copy(formErrors = mapOf("zone" to "时区格式不正确，例如 Asia/Shanghai")) }
            return
        }
        val refresh = GameRefreshRules(
            zoneId = zone,
            dailyRefresh = state.dailyRefresh,
            weeklyRefreshDay = state.weeklyRefreshDay,
            weeklyRefreshTime = state.weeklyRefreshTime,
        )
        val draft = if (state.isShort) {
            ShortOrderDraft(
                ownerId = ownerId,
                accountId = accountId,
                title = state.title,
                priceYuan = state.priceYuan,
                paymentMode = state.paymentMode,
                deadlineDate = state.deadlineDate,
                notes = state.notes,
                tasks = state.tasks,
            )
        } else {
            HostingOrderDraft(
                ownerId = ownerId,
                accountId = accountId,
                title = state.title,
                startDate = state.startDate,
                endDate = state.endDate,
                priceYuan = state.priceYuan,
                paymentMode = state.paymentMode,
                notes = state.notes,
                refresh = refresh,
                tasks = state.tasks,
            )
        }
        val errors = when (draft) {
            is ShortOrderDraft -> draft.validate()
            is HostingOrderDraft -> draft.validate()
            else -> emptyMap()
        }
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(formErrors = errors, saveError = null) }
            return
        }
        val isShort = state.isShort
        _uiState.update { it.copy(saving = true, formErrors = emptyMap(), saveError = null) }
        viewModelScope.launch {
            runCatching {
                when (draft) {
                    is ShortOrderDraft -> orderRepository.createShortOrder(draft)
                    is HostingOrderDraft -> orderRepository.createHostingOrder(draft)
                    else -> error("未知订单类型")
                }
            }.onSuccess { orderId ->
                _uiState.update { it.copy(saving = false) }
                _createdOrderId.emit(orderId)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        saving = false,
                        saveError = error.message ?: "创建订单失败，请重试",
                    )
                }
            }
        }
    }
}
