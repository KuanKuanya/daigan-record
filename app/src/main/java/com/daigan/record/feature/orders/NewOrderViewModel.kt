package com.daigan.record.feature.orders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daigan.record.core.time.GameCalendar
import com.daigan.record.core.time.GameRefreshRules
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.model.GameAccount
import com.daigan.record.data.model.HostingOrderDraft
import com.daigan.record.data.model.InitialPaymentDraft
import com.daigan.record.data.model.Money
import com.daigan.record.data.model.OrderCommandFingerprint
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.Owner
import com.daigan.record.data.model.PaymentChannel
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
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
    val initialPaymentEnabled: Boolean = false,
    val initialAmountYuan: String = "",
    val initialChannel: PaymentChannel = PaymentChannel.WECHAT,
    val initialNote: String = "",
    /** Actual first-receipt moment. Defaults to "now"; historical values are allowed. */
    val initialReceivedAtMillis: Long = 0L,
    val formErrors: Map<String, String> = emptyMap(),
    val saveError: String? = null,
    val saving: Boolean = false,
) {
    val errorMessages: List<String> get() = formErrors.values.distinct()
    val isShort: Boolean get() = type == OrderType.SHORT_TASK

    val priceCentsOrNull: Long? get() = Money.parseYuanToCentsOrNull(priceYuan)
    val initialCentsOrNull: Long? get() = Money.parsePositiveYuanToCentsOrNull(initialAmountYuan)

    /** Preview only; the repository recomputes the same numbers inside its transaction. */
    val remainingAfterInitialCents: Long?
        get() {
            if (!initialPaymentEnabled) return priceCentsOrNull
            val price = priceCentsOrNull ?: return null
            val initial = initialCentsOrNull ?: return null
            return if (initial > price) null else price - initial
        }
}

@HiltViewModel
class NewOrderViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val ownerRepository: OwnerRepository,
    private val accountRepository: GameAccountRepository,
    private val orderRepository: OrderRepository,
    private val timeProvider: TimeProvider,
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
            initialReceivedAtMillis = timeProvider.now().toEpochMilli(),
        ),
    )
    val uiState: StateFlow<NewOrderUiState> = _uiState.asStateFlow()

    private val _createdOrderId = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val createdOrderId: SharedFlow<String> = _createdOrderId

    init {
        restorePendingCommand()
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

    /**
     * Restores the exact command that was pending when the process died, including the
     * create-operation id, the initial-receipt id and the actual receipt time. Without
     * this, a retry after restoration could not be recognized as the same command.
     */
    private fun restorePendingCommand() {
        val restored = savedStateHandle.get<String>(KEY_PENDING_COMMAND)
            ?.let(PendingOrderCommandCodec::decode)
            ?: return
        savedStateHandle[KEY_CREATION_OPERATION] = restored.creationOperationId
        savedStateHandle[KEY_INITIAL_PAYMENT_OPERATION] = restored.initialPaymentOperationId
        val form = restored.form
        _uiState.update { current ->
            current.copy(
                type = if (form.isShort) OrderType.SHORT_TASK else OrderType.HOSTING,
                title = form.title,
                priceYuan = form.priceYuan,
                paymentMode = form.paymentMode,
                deadlineDate = form.deadlineDate,
                notes = form.notes,
                startDate = form.startDate,
                endDate = form.endDate,
                durationText = form.durationText,
                zoneText = form.zoneText,
                dailyRefresh = form.dailyRefresh,
                weeklyRefreshDay = form.weeklyRefreshDay,
                weeklyRefreshTime = form.weeklyRefreshTime,
                tasks = form.tasks,
                initialPaymentEnabled = form.initialPaymentEnabled,
                initialAmountYuan = form.initialAmountYuan,
                initialChannel = form.initialChannel,
                initialNote = form.initialNote,
                initialReceivedAtMillis = restored.initialReceivedAtMillis,
            )
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

    fun selectPaymentMode(mode: PaymentMode) = _uiState.update {
        it.copy(paymentMode = mode, formErrors = emptyMap(), saveError = null)
    }

    fun setInitialPaymentEnabled(enabled: Boolean) = _uiState.update {
        it.copy(initialPaymentEnabled = enabled, formErrors = emptyMap(), saveError = null)
    }

    fun updateInitialAmount(value: String) = _uiState.update { it.copy(initialAmountYuan = value) }

    fun selectInitialChannel(channel: PaymentChannel) = _uiState.update {
        it.copy(initialChannel = channel)
    }

    fun updateInitialNote(value: String) = _uiState.update { it.copy(initialNote = value) }

    /** Actual first-receipt moment; independent from the order-creation time. */
    fun updateInitialReceivedAt(instant: Instant) = _uiState.update {
        it.copy(initialReceivedAtMillis = instant.toEpochMilli())
    }

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

    /**
     * Submits the whole create-order command.
     *
     * The submission gate is acquired synchronously (before any coroutine starts), so a
     * double tap in the same frame cannot start two commands. The create-operation id,
     * the initial-receipt id, the actual receipt time and the exact form payload are
     * kept in [SavedStateHandle]; a retry after process restoration is the same
     * command. Only an edited payload (explicit new command) gets new identities.
     */
    fun submit() {
        if (_uiState.value.saving) return
        val state = _uiState.value
        if (state.loadError != null) {
            _uiState.update { it.copy(saveError = state.loadError) }
            return
        }
        // Gate first: everything below runs synchronously on the main thread.
        _uiState.update { it.copy(saving = true, formErrors = emptyMap(), saveError = null) }

        var creationOperationId = stableCreationOperationId()
        var initialPaymentOperationId = stableInitialPaymentOperationId()
        var draft = buildDraft(state, creationOperationId, initialPaymentOperationId)
        if (draft == null) {
            _uiState.update {
                it.copy(
                    saving = false,
                    formErrors = mapOf("zone" to "时区格式不正确，例如 Asia/Shanghai"),
                    saveError = null,
                )
            }
            return
        }
        val errors = when (draft) {
            is ShortOrderDraft -> draft.validate()
            is HostingOrderDraft -> draft.validate()
            else -> emptyMap()
        }
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(saving = false, formErrors = errors, saveError = null) }
            return
        }

        var fingerprint = fingerprintOf(draft)
        val pendingFingerprint = savedStateHandle.get<String>(KEY_PENDING_FINGERPRINT)
        if (pendingFingerprint != null && pendingFingerprint != fingerprint) {
            // The previous submission failed and the user changed the command. This is
            // an explicitly new command, so it must not reuse the old operation ids.
            creationOperationId = UUID.randomUUID().toString()
            initialPaymentOperationId = UUID.randomUUID().toString()
            savedStateHandle[KEY_CREATION_OPERATION] = creationOperationId
            savedStateHandle[KEY_INITIAL_PAYMENT_OPERATION] = initialPaymentOperationId
            draft = checkNotNull(buildDraft(state, creationOperationId, initialPaymentOperationId))
            fingerprint = fingerprintOf(draft)
        }

        savedStateHandle[KEY_PENDING_FINGERPRINT] = fingerprint
        savedStateHandle[KEY_PENDING_COMMAND] = PendingOrderCommandCodec.encode(
            form = state.toRestoredForm(),
            creationOperationId = creationOperationId,
            initialPaymentOperationId = initialPaymentOperationId,
            initialReceivedAtMillis = state.initialReceivedAtMillis,
        )

        viewModelScope.launch {
            runCatching {
                when (draft) {
                    is ShortOrderDraft -> orderRepository.createShortOrder(draft)
                    is HostingOrderDraft -> orderRepository.createHostingOrder(draft)
                    else -> error("未知订单类型")
                }
            }.onSuccess { orderId ->
                // The command is complete: the next tap is a new command.
                savedStateHandle.remove<String>(KEY_PENDING_FINGERPRINT)
                savedStateHandle.remove<String>(KEY_PENDING_COMMAND)
                savedStateHandle[KEY_CREATION_OPERATION] = UUID.randomUUID().toString()
                savedStateHandle[KEY_INITIAL_PAYMENT_OPERATION] = UUID.randomUUID().toString()
                _uiState.update {
                    it.copy(
                        saving = false,
                        initialReceivedAtMillis = timeProvider.now().toEpochMilli(),
                    )
                }
                _createdOrderId.emit(orderId)
            }.onFailure { error ->
                // Keep the identities and payload so a retry is the same command.
                _uiState.update {
                    it.copy(
                        saving = false,
                        saveError = error.message ?: "创建订单失败，请重试",
                    )
                }
            }
        }
    }

    private fun buildDraft(
        state: NewOrderUiState,
        creationOperationId: String,
        initialPaymentOperationId: String,
    ): Any? {
        val zone = runCatching { ZoneId.of(state.zoneText.trim()) }.getOrNull() ?: return null
        val refresh = GameRefreshRules(
            zoneId = zone,
            dailyRefresh = state.dailyRefresh,
            weeklyRefreshDay = state.weeklyRefreshDay,
            weeklyRefreshTime = state.weeklyRefreshTime,
        )
        val initialPayment = if (state.initialPaymentEnabled) {
            InitialPaymentDraft(
                amountYuan = state.initialAmountYuan,
                channel = state.initialChannel,
                note = state.initialNote,
                operationId = initialPaymentOperationId,
                receivedAt = Instant.ofEpochMilli(state.initialReceivedAtMillis),
            )
        } else {
            null
        }
        return if (state.isShort) {
            ShortOrderDraft(
                ownerId = ownerId,
                accountId = accountId,
                title = state.title,
                priceYuan = state.priceYuan,
                paymentMode = state.paymentMode,
                deadlineDate = state.deadlineDate,
                notes = state.notes,
                tasks = state.tasks,
                initialPayment = initialPayment,
                creationOperationId = creationOperationId,
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
                initialPayment = initialPayment,
                creationOperationId = creationOperationId,
            )
        }
    }

    private fun fingerprintOf(draft: Any): String = when (draft) {
        is ShortOrderDraft -> OrderCommandFingerprint.short(draft)
        is HostingOrderDraft -> OrderCommandFingerprint.hosting(draft)
        else -> error("未知订单类型")
    }

    private fun stableCreationOperationId(): String =
        savedStateHandle.get<String>(KEY_CREATION_OPERATION)
            ?: UUID.randomUUID().toString().also {
                savedStateHandle[KEY_CREATION_OPERATION] = it
            }

    private fun stableInitialPaymentOperationId(): String =
        savedStateHandle.get<String>(KEY_INITIAL_PAYMENT_OPERATION)
            ?: UUID.randomUUID().toString().also {
                savedStateHandle[KEY_INITIAL_PAYMENT_OPERATION] = it
            }

    private fun NewOrderUiState.toRestoredForm(): RestoredOrderForm = RestoredOrderForm(
        isShort = isShort,
        title = title,
        priceYuan = priceYuan,
        paymentMode = paymentMode,
        deadlineDate = deadlineDate,
        notes = notes,
        startDate = startDate,
        endDate = endDate,
        durationText = durationText,
        zoneText = zoneText,
        dailyRefresh = dailyRefresh,
        weeklyRefreshDay = weeklyRefreshDay,
        weeklyRefreshTime = weeklyRefreshTime,
        tasks = tasks,
        initialPaymentEnabled = initialPaymentEnabled,
        initialAmountYuan = initialAmountYuan,
        initialChannel = initialChannel,
        initialNote = initialNote,
    )

    private companion object {
        const val KEY_CREATION_OPERATION = "new-order.creation-operation"
        const val KEY_INITIAL_PAYMENT_OPERATION = "new-order.initial-payment-operation"
        const val KEY_PENDING_FINGERPRINT = "new-order.pending-fingerprint"
        const val KEY_PENDING_COMMAND = "new-order.pending-command"
    }
}
