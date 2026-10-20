package com.kuankuan.daigan.testing

import com.kuankuan.daigan.core.time.TimeProvider
import com.kuankuan.daigan.data.model.AccountDraft
import com.kuankuan.daigan.data.model.ArchivedAccount
import com.kuankuan.daigan.data.model.GameAccount
import com.kuankuan.daigan.data.model.LoginMethod
import com.kuankuan.daigan.data.model.Owner
import com.kuankuan.daigan.data.model.OwnerDetail
import com.kuankuan.daigan.data.model.OwnerSummary
import com.kuankuan.daigan.data.model.CorrectionResult
import com.kuankuan.daigan.data.model.PaymentRequest
import com.kuankuan.daigan.data.model.PaymentResult
import com.kuankuan.daigan.data.model.ReceiptCorrectionRequest
import com.kuankuan.daigan.data.model.ReceiptHistoryEntry
import com.kuankuan.daigan.data.repository.GameAccountRepository
import com.kuankuan.daigan.data.repository.OwnerRepository
import com.kuankuan.daigan.data.repository.ReceiptRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

val fixedInstant: Instant = Instant.parse("2026-10-17T02:00:00Z")

class FixedTimeProvider(private val instant: Instant = fixedInstant) : TimeProvider {
    override fun now(): Instant = instant
}

fun owner(
    id: String = "owner-1",
    number: Int = 1,
    nickname: String = "小满",
    archived: Boolean = false,
): Owner = Owner(
    id = id,
    number = number,
    nickname = nickname,
    createdAt = fixedInstant,
    archived = archived,
)

fun account(
    id: String = "account-1",
    ownerId: String = "owner-1",
    gameName: String = "重返未来：1999",
    server: String = "官服",
    uid: String = "10000628",
    loginAccount: String = "demo-xiaoman",
    loginMethod: LoginMethod = LoginMethod.PASSWORD,
    password: String? = "demo1999",
    archived: Boolean = false,
    archivedWithOwner: Boolean = false,
    defaultTemplateId: String? = null,
): GameAccount = GameAccount(
    id = id,
    ownerId = ownerId,
    gameName = gameName,
    server = server,
    uid = uid,
    loginAccount = loginAccount,
    loginMethod = loginMethod,
    password = password,
    createdAt = fixedInstant,
    archived = archived,
    archivedWithOwner = archivedWithOwner,
    defaultTemplateId = defaultTemplateId,
)

class FakeOwnerRepository : OwnerRepository {
    val activeOwners = MutableStateFlow<List<OwnerSummary>>(emptyList())
    val archivedOwners = MutableStateFlow<List<OwnerSummary>>(emptyList())
    val ownersById = mutableMapOf<String, Owner>()
    val renamed = mutableListOf<Pair<String, String>>()
    val createdNicknames = mutableListOf<String>()

    override fun observeActiveOwners(): Flow<List<OwnerSummary>> = activeOwners

    override fun observeArchivedOwners(): Flow<List<OwnerSummary>> = archivedOwners

    override fun observeOwner(ownerId: String): Flow<OwnerDetail?> = combine(
        activeOwners,
        archivedOwners,
    ) { active, archived ->
        val summary = (active + archived).firstOrNull { it.owner.id == ownerId }
        summary?.let { found ->
            OwnerDetail(
                owner = found.owner,
                activeAccounts = found.accounts,
                archivedAccounts = emptyList(),
            )
        }
    }

    override suspend fun findOwner(ownerId: String): Owner? = ownersById[ownerId]

    override suspend fun createOwner(nickname: String): Owner {
        // Mirrors the real repository contract: the nickname is stored trimmed.
        val trimmed = nickname.trim()
        createdNicknames += trimmed
        val created = owner(id = "owner-${ownersById.size + 1}", number = ownersById.size + 1, nickname = trimmed)
        ownersById[created.id] = created
        activeOwners.value = activeOwners.value + OwnerSummary(created, emptyList())
        return created
    }

    override suspend fun renameOwner(ownerId: String, nickname: String) {
        renamed += ownerId to nickname
    }

    override suspend fun archiveOwner(ownerId: String) = Unit

    override suspend fun restoreOwner(ownerId: String) = Unit
}

class FakeGameAccountRepository : GameAccountRepository {
    val archivedAccounts = MutableStateFlow<List<ArchivedAccount>>(emptyList())
    val accountsById = mutableMapOf<String, GameAccount>()
    val createdDrafts = mutableListOf<AccountDraft>()
    val updatedDrafts = mutableListOf<AccountDraft>()

    override fun observeArchivedAccounts(): Flow<List<ArchivedAccount>> = archivedAccounts

    override suspend fun findAccount(accountId: String): GameAccount? = accountsById[accountId]

    override suspend fun createAccount(ownerId: String, draft: AccountDraft): GameAccount {
        createdDrafts += draft
        val entity = account(
            id = "account-${accountsById.size + 1}",
            ownerId = ownerId,
            gameName = draft.gameName,
            loginMethod = draft.loginMethod,
            password = draft.password.ifEmpty { null },
            defaultTemplateId = draft.defaultTemplateId,
        )
        accountsById[entity.id] = entity
        return entity
    }

    override suspend fun updateAccount(accountId: String, draft: AccountDraft): GameAccount {
        updatedDrafts += draft
        val updated = checkNotNull(accountsById[accountId]).copy(
            gameName = draft.gameName,
            loginMethod = draft.loginMethod,
            password = draft.password.ifEmpty { null },
            defaultTemplateId = draft.defaultTemplateId,
        )
        accountsById[accountId] = updated
        return updated
    }

    override suspend fun archiveAccount(accountId: String) = Unit

    override suspend fun restoreAccount(accountId: String) = Unit
}

/**
 * Template library fake for ViewModel tests.
 *
 * It mirrors the real contract that matters to the ViewModels: built-ins are installed
 * once, a saved template keeps its id, and an import with save-as gets a new name.
 */
class FakeGameTemplateRepository : com.kuankuan.daigan.data.repository.GameTemplateRepository {
    val library = MutableStateFlow<List<com.kuankuan.daigan.data.model.GameTemplateSummary>>(emptyList())
    val templatesById = mutableMapOf<String, com.kuankuan.daigan.data.model.GameTemplate>()
    var ensureCalls = 0
    var saveCalls = 0
    var deleted = mutableListOf<String>()

    fun put(template: com.kuankuan.daigan.data.model.GameTemplate) {
        templatesById[template.id] = template
        val tasks = template.tasks
        val summary = com.kuankuan.daigan.data.model.GameTemplateSummary(
            id = template.id,
            gameName = template.gameName,
            templateVersion = template.templateVersion,
            gameVersionNotes = template.gameVersionNotes,
            refresh = template.refresh,
            origin = template.origin,
            categoryCount = template.categories.size,
            taskCount = tasks.size,
            hostingTaskCount = tasks.count {
                it.recurrence != com.kuankuan.daigan.data.model.RecurrenceType.ONE_TIME
            },
            shortTaskCount = tasks.count {
                it.recurrence == com.kuankuan.daigan.data.model.RecurrenceType.ONE_TIME
            },
        )
        library.value = library.value.filterNot { it.id == summary.id } + summary
    }

    override fun observeLibrary(): Flow<List<com.kuankuan.daigan.data.model.GameTemplateSummary>> =
        library

    override fun observeTemplate(
        templateId: String,
    ): Flow<com.kuankuan.daigan.data.model.GameTemplate?> =
        library.map { templatesById[templateId] }

    override suspend fun findTemplate(
        templateId: String,
    ): com.kuankuan.daigan.data.model.GameTemplate? = templatesById[templateId]

    override suspend fun findAllSummaries(): List<com.kuankuan.daigan.data.model.GameTemplateSummary> =
        library.value

    override suspend fun findNameConflict(
        gameName: String,
    ): com.kuankuan.daigan.data.model.GameTemplateSummary? = library.value.firstOrNull {
        com.kuankuan.daigan.data.template.TemplateNames.normalize(it.gameName) ==
            com.kuankuan.daigan.data.template.TemplateNames.normalize(gameName)
    }

    override suspend fun ensureBuiltInTemplates() {
        ensureCalls += 1
    }

    override suspend fun saveTemplate(
        draft: com.kuankuan.daigan.data.model.TemplateDraft,
        templateId: String?,
    ): String {
        saveCalls += 1
        val id = templateId ?: "template-${saveCalls}"
        val now = fixedInstant
        val template = com.kuankuan.daigan.data.model.GameTemplate(
            id = id,
            gameName = draft.gameName,
            templateVersion = draft.templateVersion,
            gameVersionNotes = draft.gameVersionNotes.ifBlank { null },
            refresh = com.kuankuan.daigan.data.model.GameRefreshRulesDomain(
                zoneId = java.time.ZoneId.of(draft.zoneText),
                dailyRefresh = draft.dailyRefresh,
                weeklyRefreshDay = draft.weeklyRefreshDay,
                weeklyRefreshTime = draft.weeklyRefreshTime,
            ),
            origin = templateId?.let { templatesById[it]?.origin }
                ?: com.kuankuan.daigan.data.model.TemplateOrigin.USER,
            builtInKey = null,
            createdAt = now,
            updatedAt = now,
            categories = draft.categories.mapIndexed { categoryIndex, category ->
                com.kuankuan.daigan.data.model.TemplateCategory(
                    id = category.id,
                    templateId = id,
                    name = category.name,
                    sortOrder = categoryIndex,
                    tasks = category.tasks.mapIndexed { taskIndex, task ->
                        com.kuankuan.daigan.data.model.TemplateTask(
                            id = task.id,
                            templateId = id,
                            categoryId = category.id,
                            title = task.title,
                            progressType = task.progressType,
                            startValue = task.startDecimal() ?: java.math.BigDecimal.ZERO,
                            targetValue = task.targetDecimal() ?: java.math.BigDecimal.ONE,
                            unit = task.unit.ifBlank { null },
                            recurrence = task.recurrence,
                            weekdays = task.weekdays,
                            everyNDays = task.everyNOrNull(),
                            sortOrder = taskIndex,
                        )
                    },
                )
            },
        )
        put(template)
        return id
    }

    override suspend fun deleteTemplate(templateId: String) {
        deleted += templateId
        templatesById.remove(templateId)
        library.value = library.value.filterNot { it.id == templateId }
    }

    override suspend fun importTemplate(
        content: com.kuankuan.daigan.data.template.TemplateFileContent,
        resolution: com.kuankuan.daigan.data.repository.TemplateImportResolution,
    ): com.kuankuan.daigan.data.repository.ImportedTemplate = error("not used by ViewModel tests")
}

/** Exposes the repository flows for assertions without extra test utilities. */
fun OwnerRepository.activeOwnerIds(): Flow<List<String>> =
    observeActiveOwners().map { owners -> owners.map { it.owner.id } }

/**
 * Financial fake for ViewModel tests that only exercise order-detail actions. A test
 * that needs real money guarantees uses the real Room environment instead.
 */
class FakeReceiptRepository : ReceiptRepository {
    val recordedRequests = mutableListOf<PaymentRequest>()
    val corrections = mutableListOf<ReceiptCorrectionRequest>()

    override fun observeReceiptHistory(): Flow<List<ReceiptHistoryEntry>> = flowOf(emptyList())

    override suspend fun recordPayment(request: PaymentRequest): PaymentResult =
        error("FakeReceiptRepository cannot record payments; use the Room environment")

    override suspend fun correctReceipt(request: ReceiptCorrectionRequest): CorrectionResult =
        error("FakeReceiptRepository cannot correct receipts; use the Room environment")
}

class FakeOrderRepository : com.kuankuan.daigan.data.repository.OrderRepository {
    val hosting = MutableStateFlow<List<com.kuankuan.daigan.data.model.TodayHostingOrder>>(emptyList())
    val shortOrders = MutableStateFlow<List<com.kuankuan.daigan.data.model.ShortWorkOrder>>(emptyList())
    val details = MutableStateFlow<Map<String, com.kuankuan.daigan.data.model.OrderDetail>>(emptyMap())
    val completedBatches = mutableListOf<Pair<String, String>>()

    override fun observeOrdersForOwner(ownerId: String): Flow<List<com.kuankuan.daigan.data.model.OrderSummary>> =
        MutableStateFlow(emptyList())

    override fun observeOrderDetail(orderId: String): Flow<com.kuankuan.daigan.data.model.OrderDetail?> =
        details.map { it[orderId] }

    override fun observeTodayHosting() = hosting

    override fun observeTodayShort() = shortOrders

    override suspend fun createShortOrder(draft: com.kuankuan.daigan.data.model.ShortOrderDraft): String =
        "short-order"

    override suspend fun createHostingOrder(draft: com.kuankuan.daigan.data.model.HostingOrderDraft): String =
        "hosting-order"

    override suspend fun setShortTaskCompleted(taskId: String, completed: Boolean) = Unit

    override suspend fun updateShortTaskProgress(
        taskId: String,
        value: java.math.BigDecimal,
    ) = Unit

    override suspend fun completeShortOrder(
        orderId: String,
        operationId: String,
    ): com.kuankuan.daigan.data.model.BulkCompletionResult {
        completedBatches += operationId to orderId
        return com.kuankuan.daigan.data.model.BulkCompletionResult("batch", 1, 0, false)
    }

    override suspend fun setOccurrenceCompleted(
        taskId: String,
        completed: Boolean,
        expectedOccurrenceKey: String?,
    ) = Unit

    override suspend fun updateOccurrenceProgress(
        taskId: String,
        value: java.math.BigDecimal,
        expectedOccurrenceKey: String?,
    ) = Unit

    override suspend fun completeHostingOrder(
        orderId: String,
        operationId: String,
        displayed: com.kuankuan.daigan.data.repository.DisplayedHostingSnapshot,
    ): com.kuankuan.daigan.data.model.BulkCompletionResult =
        com.kuankuan.daigan.data.model.BulkCompletionResult("batch", 1, 0, false)

    override suspend fun completeAllHosting(
        operationId: String,
        displayed: List<com.kuankuan.daigan.data.repository.DisplayedHostingSnapshot>,
    ): com.kuankuan.daigan.data.model.BulkCompletionResult =
        com.kuankuan.daigan.data.model.BulkCompletionResult("batch", 1, 0, false)

    override suspend fun undoBatch(batchId: String): com.kuankuan.daigan.data.model.UndoResult =
        com.kuankuan.daigan.data.model.UndoResult(1, emptyList(), false)

    override suspend fun setHistoricalCompletion(
        orderId: String,
        periodId: String,
        taskId: String,
        date: java.time.LocalDate,
        completed: Boolean,
        weeklyWindowStart: java.time.LocalDate?,
    ): Boolean = true

    override suspend fun renewHosting(
        orderId: String,
        operationId: String,
        durationDays: Int,
        priceCents: Long,
    ): com.kuankuan.daigan.data.model.RenewalResult = com.kuankuan.daigan.data.model.RenewalResult(
        periodId = "period-2",
        periodIndex = 1,
        startDate = java.time.LocalDate.of(2026, 10, 18),
        endDate = java.time.LocalDate.of(2026, 11, 16),
        alreadyApplied = false,
    )
}
