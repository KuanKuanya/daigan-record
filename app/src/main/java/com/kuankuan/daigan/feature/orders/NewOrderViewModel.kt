package com.kuankuan.daigan.feature.orders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kuankuan.daigan.core.time.GameCalendar
import com.kuankuan.daigan.core.time.GameRefreshRules
import com.kuankuan.daigan.core.time.TimeProvider
import com.kuankuan.daigan.data.model.GameAccount
import com.kuankuan.daigan.data.model.GameTemplate
import com.kuankuan.daigan.data.model.GameTemplateSummary
import com.kuankuan.daigan.data.model.HostingOrderDraft
import com.kuankuan.daigan.data.model.InitialPaymentDraft
import com.kuankuan.daigan.data.model.Money
import com.kuankuan.daigan.data.model.OrderCommandFingerprint
import com.kuankuan.daigan.data.model.OrderTemplateProvenance
import com.kuankuan.daigan.data.model.OrderType
import com.kuankuan.daigan.data.model.Owner
import com.kuankuan.daigan.data.model.PaymentChannel
import com.kuankuan.daigan.data.model.PaymentMode
import com.kuankuan.daigan.data.model.ProgressType
import com.kuankuan.daigan.data.model.RecurrenceType
import com.kuankuan.daigan.data.model.ShortOrderDraft
import com.kuankuan.daigan.data.model.TEMPLATE_NO_HOSTING_TASKS_MESSAGE
import com.kuankuan.daigan.data.model.TEMPLATE_NO_SHORT_TASKS_MESSAGE
import com.kuankuan.daigan.data.model.TaskDraft
import com.kuankuan.daigan.data.model.TemplateOrderKind
import com.kuankuan.daigan.data.model.toTaskDraft
import com.kuankuan.daigan.data.repository.GameAccountRepository
import com.kuankuan.daigan.data.repository.GameTemplateRepository
import com.kuankuan.daigan.data.repository.OrderRepository
import com.kuankuan.daigan.data.repository.OwnerRepository
import com.kuankuan.daigan.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
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
    /** Template library and the currently applied creation defaults (optional). */
    val templates: List<GameTemplateSummary> = emptyList(),
    val selectedTemplateId: String? = null,
    val selectedTemplateName: String? = null,
    val selectedTemplateVersion: String? = null,
    val templateNotice: String? = null,
    val formErrors: Map<String, String> = emptyMap(),
    val saveError: String? = null,
    val saving: Boolean = false,
    /**
     * The order this form instance committed. Non-null is a terminal successful state:
     * the form itself is already an order, so another tap on it is not a new order
     * intent. It is persisted and only cleared by [NewOrderViewModel.startNewCreation].
     */
    val createdOrderId: String? = null,
    /**
     * True while the committed result has not been handed to the navigation host yet.
     * State (not a replay-zero event), so a collector that attaches or reattaches after
     * completion still observes the destination; acknowledging it stops replays.
     */
    val createdNavigationPending: Boolean = false,
) {
    val errorMessages: List<String> get() = formErrors.values.distinct()
    val isShort: Boolean get() = type == OrderType.SHORT_TASK
    val isCreated: Boolean get() = createdOrderId != null

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
    private val templateRepository: GameTemplateRepository,
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

    /**
     * The tasks produced by the last template application. Used only to decide whether a
     * type switch may re-derive the defaults: once the user edits a task, their work is
     * never silently replaced. Not persisted; after process restoration the form keeps
     * the exact tasks from the pending command instead of re-applying a template.
     */
    private var lastAppliedTasks: List<TaskDraft>? = null
    private var restoredPendingCommand = false
    private var explicitShortCalendar = true
    private var templateIntent = 0L

    /** Every newer form decision invalidates older asynchronous default reads. */
    private fun invalidateTemplateReads() {
        templateIntent++
    }

    private fun canApplyTemplate(intent: Long, type: OrderType): Boolean =
        intent == templateIntent && _uiState.value.type == type &&
            !_uiState.value.saving && !_uiState.value.isCreated

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
            runCatching { templateRepository.ensureBuiltInTemplates() }
            val templates = runCatching { templateRepository.findAllSummaries() }
                .getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    loading = false,
                    loadError = error,
                    owner = owner,
                    account = account,
                    templates = templates,
                )
            }
            if (error == null && templateIntent == 0L && !restoredPendingCommand && !_uiState.value.isCreated) {
                applyAccountDefaultTemplate(account?.defaultTemplateId, templates)
            }
        }
    }

    /**
     * Preselects the account's default template and fills the form once. A missing or
     * deleted template is ignored; an account never requires a template to create an
     * order, and a restored pending command is left exactly as it was.
     */
    private suspend fun applyAccountDefaultTemplate(
        templateId: String?,
        templates: List<GameTemplateSummary>,
    ) {
        if (templateId == null || templates.none { it.id == templateId }) return
        val intent = templateIntent
        val type = _uiState.value.type
        val template = runCatching { templateRepository.findTemplate(templateId) }.getOrNull()
            ?: return
        if (!canApplyTemplate(intent, type)) return
        applyTemplateDefaults(template, type)
        _uiState.update {
            it.copy(
                templateNotice = "已按账号默认模板填入「${template.gameName} · " +
                    "${template.templateVersion}」；可随时修改或清除。",
            )
        }
    }

    /**
     * Restores the exact command that was pending when the process died, including the
     * create-operation id, the initial-receipt id and the actual receipt time. Without
     * this, a retry after restoration could not be recognized as the same command.
     */
    private fun restorePendingCommand() {
        restoreCommittedCreation()
        val restored = savedStateHandle.get<String>(KEY_PENDING_COMMAND)
            ?.let(PendingOrderCommandCodec::decode)
            ?: return
        restoredPendingCommand = true
        savedStateHandle[KEY_CREATION_OPERATION] = restored.creationOperationId
        savedStateHandle[KEY_INITIAL_PAYMENT_OPERATION] = restored.initialPaymentOperationId
        val form = restored.form
        explicitShortCalendar = form.explicitShortCalendar
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
                selectedTemplateId = form.sourceTemplateId,
                selectedTemplateName = form.sourceTemplateName,
                selectedTemplateVersion = form.sourceTemplateVersion,
            )
        }
    }

    /**
     * A restored form that already committed an order must not look like an empty form:
     * keeping the result in state lets the screen reopen the original destination and
     * prevents a second create command from the restored instance.
     */
    private fun restoreCommittedCreation() {
        val createdOrderId = savedStateHandle.get<String>(KEY_CREATED_ORDER_ID) ?: return
        val pending = savedStateHandle.get<Boolean>(KEY_CREATED_NAVIGATION_PENDING) ?: true
        _uiState.update {
            it.copy(
                createdOrderId = createdOrderId,
                createdNavigationPending = pending,
                saving = false,
            )
        }
    }

    fun selectType(type: OrderType) {
        val previous = _uiState.value
        if (previous.type == type || previous.saving || previous.isCreated) return
        invalidateTemplateReads()
        explicitShortCalendar = true
        val intent = templateIntent
        // Re-derive the template defaults only while the task list is still exactly what
        // the template produced. Edited tasks are the user's work and are never replaced.
        val mayReapply = previous.selectedTemplateId != null &&
            lastAppliedTasks != null &&
            previous.tasks == lastAppliedTasks
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
        val templateId = previous.selectedTemplateId
        if (mayReapply && templateId != null) {
            viewModelScope.launch {
                val template = runCatching { templateRepository.findTemplate(templateId) }
                    .getOrNull()
                    ?: return@launch
                if (canApplyTemplate(intent, type)) applyTemplateDefaults(template, type)
            }
        } else if (previous.selectedTemplateId != null) {
            _uiState.update {
                it.copy(
                    templateNotice = "已切换订单类型；当前任务保留你的编辑，可重新选择模板以套用默认值。",
                )
            }
        }
    }

    /**
     * Fills task defaults and refresh rules from one template. The template is a creation
     * default only; the created order stores its own copies and provenance label.
     */
    fun applyTemplate(templateId: String) {
        val state = _uiState.value
        if (state.isCreated || state.saving) return
        invalidateTemplateReads()
        val intent = templateIntent
        val type = state.type
        viewModelScope.launch {
            val template = runCatching { templateRepository.findTemplate(templateId) }.getOrNull()
            if (!canApplyTemplate(intent, type)) return@launch
            if (template == null) {
                _uiState.update { it.copy(templateNotice = "模板不存在，可能已被删除") }
                return@launch
            }
            applyTemplateDefaults(template, type)
        }
    }

    /** Drops the template label and notice; the current tasks stay as editable drafts. */
    fun clearTemplate() {
        if (_uiState.value.saving || _uiState.value.isCreated) return
        invalidateTemplateReads()
        lastAppliedTasks = null
        _uiState.update {
            it.copy(
                selectedTemplateId = null,
                selectedTemplateName = null,
                selectedTemplateVersion = null,
                templateNotice = "已清除模板默认值；当前任务仍可继续编辑。",
            )
        }
    }

    private fun applyTemplateDefaults(
        template: GameTemplate,
        type: OrderType,
    ) {
        if (_uiState.value.isCreated || _uiState.value.saving) return
        val kind = if (type == OrderType.SHORT_TASK) {
            TemplateOrderKind.SHORT_TASK
        } else {
            TemplateOrderKind.HOSTING
        }
        val applicable = template.tasksFor(kind)
        if (applicable.isEmpty()) {
            // Never reinterpret a one-time task as a daily occurrence, and never wipe the
            // tasks the user already has because a template does not fit this order type.
            _uiState.update {
                it.copy(
                    templateNotice = if (kind == TemplateOrderKind.HOSTING) {
                        "「${template.gameName}」$TEMPLATE_NO_HOSTING_TASKS_MESSAGE"
                    } else {
                        "「${template.gameName}」$TEMPLATE_NO_SHORT_TASKS_MESSAGE"
                    },
                )
            }
            return
        }
        explicitShortCalendar = true
        val tasks = applicable.map { it.toTaskDraft() }
        lastAppliedTasks = tasks
        _uiState.update {
            it.copy(
                tasks = tasks,
                zoneText = template.refresh.zoneId.id,
                dailyRefresh = template.refresh.dailyRefresh,
                weeklyRefreshDay = template.refresh.weeklyRefreshDay,
                weeklyRefreshTime = template.refresh.weeklyRefreshTime,
                selectedTemplateId = template.id,
                selectedTemplateName = template.gameName,
                selectedTemplateVersion = template.templateVersion,
                templateNotice = "已填入「${template.gameName} · ${template.templateVersion}」" +
                    "的 ${tasks.size} 项任务与刷新规则；仍可修改。",
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

    fun setZoneText(value: String) {
        invalidateTemplateReads()
        explicitShortCalendar = true
        _uiState.update { it.copy(zoneText = value) }
    }

    fun setDailyRefresh(time: LocalTime) {
        invalidateTemplateReads()
        explicitShortCalendar = true
        _uiState.update { it.copy(dailyRefresh = time) }
    }

    fun setWeeklyRefreshDay(day: DayOfWeek) {
        invalidateTemplateReads()
        explicitShortCalendar = true
        _uiState.update { it.copy(weeklyRefreshDay = day) }
    }

    fun setWeeklyRefreshTime(time: LocalTime) {
        invalidateTemplateReads()
        explicitShortCalendar = true
        _uiState.update { it.copy(weeklyRefreshTime = time) }
    }

    fun addTask() {
        invalidateTemplateReads()
        _uiState.update { it.copy(tasks = it.tasks + TaskDraft()) }
    }

    fun removeTask(index: Int) {
        invalidateTemplateReads()
        _uiState.update { state ->
            if (state.tasks.size <= 1) state else state.copy(
                tasks = state.tasks.filterIndexed { i, _ -> i != index },
            )
        }
    }

    fun updateTask(index: Int, transform: (TaskDraft) -> TaskDraft) {
        invalidateTemplateReads()
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
     *
     * A committed creation is terminal for this form instance: the committed order id
     * stays in state until navigation is acknowledged or [startNewCreation] is called,
     * so a repeated tap on the visible successful form never allocates new ids.
     */
    fun submit() {
        val state = _uiState.value
        if (state.isCreated || state.saving) return
        if (state.loadError != null) {
            _uiState.update { it.copy(saveError = state.loadError) }
            return
        }
        invalidateTemplateReads()
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
                // Committed. Keep the order id, the operation ids, the fingerprint, the
                // exact submitted receipt time and the payload: this form instance is
                // now terminal, and a repeated tap must not become another order.
                savedStateHandle[KEY_CREATED_ORDER_ID] = orderId
                savedStateHandle[KEY_CREATED_NAVIGATION_PENDING] = true
                _uiState.update {
                    it.copy(
                        saving = false,
                        createdOrderId = orderId,
                        createdNavigationPending = true,
                    )
                }
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

    /**
     * The navigation host has taken the committed result. The form stays terminal and
     * keeps its order id; only the navigation signal stops, so a configuration change
     * or a recomposition cannot navigate to the same order twice.
     */
    fun acknowledgeCreatedNavigation(orderId: String) {
        val current = _uiState.value
        if (current.createdOrderId != orderId || !current.createdNavigationPending) return
        savedStateHandle[KEY_CREATED_NAVIGATION_PENDING] = false
        _uiState.update { it.copy(createdNavigationPending = false) }
    }

    /**
     * The only way out of the terminal successful state. Called from an explicit user
     * action ("再建一张"), it drops the committed identity, forgets the completed
     * command and allocates new ids for the next, genuinely new order.
     */
    fun startNewCreation() {
        if (_uiState.value.saving) return
        invalidateTemplateReads()
        explicitShortCalendar = true
        savedStateHandle.remove<String>(KEY_CREATED_ORDER_ID)
        savedStateHandle.remove<Boolean>(KEY_CREATED_NAVIGATION_PENDING)
        savedStateHandle.remove<String>(KEY_PENDING_FINGERPRINT)
        savedStateHandle.remove<String>(KEY_PENDING_COMMAND)
        savedStateHandle[KEY_CREATION_OPERATION] = UUID.randomUUID().toString()
        savedStateHandle[KEY_INITIAL_PAYMENT_OPERATION] = UUID.randomUUID().toString()
        _uiState.update {
            it.copy(
                createdOrderId = null,
                createdNavigationPending = false,
                saving = false,
                saveError = null,
                formErrors = emptyMap(),
                initialReceivedAtMillis = timeProvider.now().toEpochMilli(),
            )
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
        // Provenance is a truthful label captured at creation. It never references the
        // editable template row, so later template changes cannot rewrite this order.
        val provenance = state.selectedTemplateId
            ?.takeIf {
                !state.selectedTemplateName.isNullOrBlank() &&
                    !state.selectedTemplateVersion.isNullOrBlank()
            }
            ?.let { id ->
                OrderTemplateProvenance(
                    templateId = id,
                    gameName = checkNotNull(state.selectedTemplateName),
                    templateVersion = checkNotNull(state.selectedTemplateVersion),
                )
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
                refresh = if (explicitShortCalendar) refresh else null,
                sourceTemplate = provenance,
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
                sourceTemplate = provenance,
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
        explicitShortCalendar = explicitShortCalendar,
        sourceTemplateId = selectedTemplateId,
        sourceTemplateName = selectedTemplateName,
        sourceTemplateVersion = selectedTemplateVersion,
    )

    private companion object {
        const val KEY_CREATION_OPERATION = "new-order.creation-operation"
        const val KEY_INITIAL_PAYMENT_OPERATION = "new-order.initial-payment-operation"
        const val KEY_PENDING_FINGERPRINT = "new-order.pending-fingerprint"
        const val KEY_PENDING_COMMAND = "new-order.pending-command"
        const val KEY_CREATED_ORDER_ID = "new-order.created-order-id"
        const val KEY_CREATED_NAVIGATION_PENDING = "new-order.created-navigation-pending"
    }
}
