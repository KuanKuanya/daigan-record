package com.daigan.record.data.repository

import androidx.room.withTransaction
import com.daigan.record.core.time.PersistedInstant
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.local.dao.TemplateDao
import com.daigan.record.data.local.entity.GameTemplateEntity
import com.daigan.record.data.local.entity.TemplateCategoryEntity
import com.daigan.record.data.local.entity.TemplateSeedStateEntity
import com.daigan.record.data.local.entity.TemplateTaskEntity
import com.daigan.record.data.local.toModel
import com.daigan.record.data.local.toSummary
import com.daigan.record.data.model.GameTemplate
import com.daigan.record.data.model.GameTemplateSummary
import com.daigan.record.data.model.TemplateCategoryDraft
import com.daigan.record.data.model.TemplateDraft
import com.daigan.record.data.model.TemplateOrigin
import com.daigan.record.data.model.TemplateTaskDraft
import com.daigan.record.data.template.BuiltInTemplateSource
import com.daigan.record.data.template.BuiltInTemplates
import com.daigan.record.data.template.TemplateFileCodec
import com.daigan.record.data.template.TemplateFileContent
import com.daigan.record.data.template.TemplateFileResult
import com.daigan.record.data.template.TemplateNames
import com.daigan.record.data.local.encodeWeekdays
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How an accepted import resolves a same-name conflict. Cancel is a UI-only action. */
sealed interface TemplateImportResolution {
    /** Create a new library entry with a name that cannot collide with an existing one. */
    data object SaveAs : TemplateImportResolution

    /** Replace exactly this template's content, keeping its library identity. */
    data class Replace(val templateId: String) : TemplateImportResolution
}

data class ImportedTemplate(
    val templateId: String,
    val gameName: String,
    val replaced: Boolean,
)

/**
 * Template library boundary.
 *
 * Templates are persisted creation defaults. They copy into an order at creation; no
 * order row references a template, so every mutation here is safe for existing orders,
 * periods, occurrences, receipts and corrections. Built-in initialization is guarded by
 * a durable per-key marker and never overwrites an edit or resurrection a deletion.
 */
interface GameTemplateRepository {

    fun observeLibrary(): Flow<List<GameTemplateSummary>>

    fun observeTemplate(templateId: String): Flow<GameTemplate?>

    suspend fun findTemplate(templateId: String): GameTemplate?

    /** One-shot read for pickers; ordered like [observeLibrary]. */
    suspend fun findAllSummaries(): List<GameTemplateSummary>

    /** Case-insensitive (by normalized name) lookup used for the import conflict choice. */
    suspend fun findNameConflict(gameName: String): GameTemplateSummary?

    /** Installs missing built-ins once per seed key. Safe to call from any entry point. */
    suspend fun ensureBuiltInTemplates()

    /** Creates ([templateId] null) or updates one template with explicit migrations. */
    suspend fun saveTemplate(draft: TemplateDraft, templateId: String? = null): String

    /** Removes a template and its content; orders and their snapshots are untouched. */
    suspend fun deleteTemplate(templateId: String)

    /**
     * Applies an already validated import **atomically**. A failure rolls back every row,
     * so the library is never left half-imported.
     */
    suspend fun importTemplate(
        content: TemplateFileContent,
        resolution: TemplateImportResolution,
    ): ImportedTemplate
}

@Singleton
class DefaultGameTemplateRepository @Inject constructor(
    private val database: RecordDatabase,
    private val templateDao: TemplateDao,
    private val builtInSource: BuiltInTemplateSource,
    private val timeProvider: TimeProvider,
) : GameTemplateRepository {

    private val seedMutex = Mutex()

    override fun observeLibrary(): Flow<List<GameTemplateSummary>> = combine(
        templateDao.observeTemplates(),
        templateDao.observeCategories(),
        templateDao.observeTasks(),
    ) { templates, categories, tasks ->
        val categoriesByTemplate = categories.groupBy { it.templateId }
        templates.map { template ->
            template.toSummary(
                categories = categoriesByTemplate[template.id].orEmpty(),
                tasks = tasks.filter { it.templateId == template.id },
            )
        }
    }

    override fun observeTemplate(templateId: String): Flow<GameTemplate?> = combine(
        templateDao.observeTemplates(),
        templateDao.observeCategories(),
        templateDao.observeTasks(),
    ) { templates, categories, tasks ->
        templates.firstOrNull { it.id == templateId }?.toModel(
            categories = categories.filter { it.templateId == templateId },
            tasks = tasks.filter { it.templateId == templateId },
        )
    }

    override suspend fun findTemplate(templateId: String): GameTemplate? =
        database.withDatasetTransaction {
            val template = templateDao.findTemplate(templateId) ?: return@withDatasetTransaction null
            template.toModel(
                categories = templateDao.findCategories(templateId),
                tasks = templateDao.findTasks(templateId),
            )
        }

    override suspend fun findAllSummaries(): List<GameTemplateSummary> =
        database.withDatasetTransaction {
            val templates = templateDao.findAllTemplates()
            val categories = templates.associate { it.id to templateDao.findCategories(it.id) }
            val tasks = templates.associate { it.id to templateDao.findTasks(it.id) }
            templates.map { template ->
                template.toSummary(
                    categories = categories[template.id].orEmpty(),
                    tasks = tasks[template.id].orEmpty(),
                )
            }
        }

    override suspend fun findNameConflict(gameName: String): GameTemplateSummary? {
        val target = TemplateNames.normalize(gameName)
        if (target.isEmpty()) return null
        val templates = templateDao.findAllTemplates()
        val match = templates.firstOrNull { TemplateNames.normalize(it.gameName) == target }
            ?: return null
        return match.toSummary(
            categories = templateDao.findCategories(match.id),
            tasks = templateDao.findTasks(match.id),
        )
    }

    override suspend fun ensureBuiltInTemplates() {
        seedMutex.withLock {
            database.withDatasetTransaction {
                val removedKeys = listOf(
                    BuiltInTemplates.REVERSE_1999_KEY,
                    BuiltInTemplates.WUTHERING_WAVES_KEY,
                )
                templateDao.findTemplatesByBuiltInKeys(removedKeys).forEach { legacy ->
                    if (legacy.origin == TemplateOrigin.BUILT_IN) {
                        templateDao.clearAccountDefault(legacy.id)
                        templateDao.deleteTasks(legacy.id)
                        templateDao.deleteCategories(legacy.id)
                        templateDao.deleteTemplate(legacy.id)
                    }
                }
                removedKeys.forEach { key ->
                    if (templateDao.seedAppliedCount(key) == 0) {
                        templateDao.insertSeedState(TemplateSeedStateEntity(key, now()))
                    }
                }
            }
            BuiltInTemplates.ALL.forEachIndexed { descriptorIndex, descriptor ->
                if (templateDao.seedAppliedCount(descriptor.seedKey) > 0) return@forEachIndexed
                val bytes = runCatching { builtInSource.read(descriptor.assetPath) }.getOrNull()
                    ?: return@forEachIndexed
                val content = (TemplateFileCodec.decode(bytes) as? TemplateFileResult.Success)
                    ?.content
                    ?: return@forEachIndexed
                database.withDatasetTransaction {
                    if (templateDao.seedAppliedCount(descriptor.seedKey) > 0) {
                        return@withDatasetTransaction
                    }
                    if (templateDao.findTemplateByBuiltInKey(descriptor.seedKey) != null) {
                        templateDao.insertSeedState(
                            TemplateSeedStateEntity(descriptor.seedKey, now()),
                        )
                        return@withDatasetTransaction
                    }
                    insertNew(
                        templateId = content.templateId,
                        draft = content.draft,
                        origin = TemplateOrigin.BUILT_IN,
                        builtInKey = descriptor.seedKey,
                        sortOrder = descriptorIndex,
                        now = now(),
                    )
                    templateDao.insertSeedState(
                        TemplateSeedStateEntity(descriptor.seedKey, now()),
                    )
                }
            }
        }
    }

    override suspend fun saveTemplate(draft: TemplateDraft, templateId: String?): String {
        val normalized = draft.normalized()
        requireValid(normalized)
        val now = now()
        return database.withDatasetTransaction {
            val existing = templateId?.let {
                templateDao.findTemplate(it) ?: throw IllegalArgumentException("模板不存在")
            }
            if (existing == null) {
                insertNew(
                    templateId = UUID.randomUUID().toString(),
                    draft = normalized,
                    origin = TemplateOrigin.USER,
                    builtInKey = null,
                    sortOrder = nextSortOrder(),
                    now = now,
                )
            } else {
                replaceContent(
                    existing = existing,
                    draft = normalized,
                    origin = existing.origin,
                    builtInKey = existing.builtInKey,
                    now = now,
                )
                existing.id
            }
        }
    }

    override suspend fun deleteTemplate(templateId: String) {
        database.withDatasetTransaction {
            val template = templateDao.findTemplate(templateId) ?: return@withDatasetTransaction
            templateDao.clearAccountDefault(templateId)
            templateDao.deleteTasks(templateId)
            templateDao.deleteCategories(templateId)
            templateDao.deleteTemplate(template.id)
        }
    }

    override suspend fun importTemplate(
        content: TemplateFileContent,
        resolution: TemplateImportResolution,
    ): ImportedTemplate {
        val normalized = content.draft.normalized()
        requireValid(normalized)
        val now = now()
        return database.withDatasetTransaction {
            when (resolution) {
                TemplateImportResolution.SaveAs -> {
                    val existingNames = templateDao.findAllTemplates().map { it.gameName }
                    val uniqueName = TemplateNames.uniqueGameName(
                        base = normalized.gameName,
                        existingNames = existingNames,
                    )
                    val id = insertNew(
                        templateId = UUID.randomUUID().toString(),
                        draft = normalized.copy(gameName = uniqueName),
                        origin = TemplateOrigin.IMPORTED,
                        builtInKey = null,
                        sortOrder = nextSortOrder(),
                        now = now,
                    )
                    ImportedTemplate(templateId = id, gameName = uniqueName, replaced = false)
                }

                is TemplateImportResolution.Replace -> {
                    val existing = templateDao.findTemplate(resolution.templateId)
                        ?: throw IllegalArgumentException("要替换的模板不存在")
                    replaceContent(
                        existing = existing,
                        draft = normalized,
                        origin = TemplateOrigin.IMPORTED,
                        // Keep the built-in identity so the seed marker and key still
                        // describe this library entry; the content is the imported one.
                        builtInKey = existing.builtInKey,
                        now = now,
                    )
                    ImportedTemplate(
                        templateId = existing.id,
                        gameName = normalized.gameName,
                        replaced = true,
                    )
                }
            }
        }
    }

    /**
     * Inserts a template and every child row with collision-free ids. Draft ids are
     * honoured when they are free, which keeps a built-in seed's identity stable.
     */
    private suspend fun insertNew(
        templateId: String,
        draft: TemplateDraft,
        origin: TemplateOrigin,
        builtInKey: String?,
        sortOrder: Int,
        now: Instant,
    ): String {
        val usedCategoryIds = templateDao.allCategoryIds().toMutableSet()
        val usedTaskIds = templateDao.allTaskIds().toMutableSet()
        val id = if (templateDao.findTemplate(templateId) == null) {
            templateId
        } else {
            UUID.randomUUID().toString()
        }
        val (categories, tasks) = allocateChildren(id, draft, usedCategoryIds, usedTaskIds)
        templateDao.insertTemplate(
            GameTemplateEntity(
                id = id,
                gameName = draft.gameName,
                templateVersion = draft.templateVersion,
                gameVersionNotes = draft.gameVersionNotes.ifBlank { null },
                zoneId = checkNotNull(draft.zoneOrNull()).id,
                dailyRefreshMinutes = draft.dailyRefresh.hour * 60 + draft.dailyRefresh.minute,
                weeklyRefreshDay = draft.weeklyRefreshDay.value,
                weeklyRefreshMinutes =
                draft.weeklyRefreshTime.hour * 60 + draft.weeklyRefreshTime.minute,
                origin = origin,
                builtInKey = builtInKey,
                sortOrder = sortOrder,
                createdAt = now,
                updatedAt = now,
            ),
        )
        templateDao.insertCategories(categories)
        if (tasks.isNotEmpty()) templateDao.insertTasks(tasks)
        return id
    }

    /**
     * Replaces the editable content of an existing template in place: the template row
     * keeps its id (and built-in key), every child row is rewritten with new, globally
     * unique ids. No order snapshot references these rows.
     */
    private suspend fun replaceContent(
        existing: GameTemplateEntity,
        draft: TemplateDraft,
        origin: TemplateOrigin,
        builtInKey: String?,
        now: Instant,
    ) {
        val oldCategoryIds = templateDao.findCategories(existing.id).map { it.id }.toSet()
        val oldTaskIds = templateDao.findTasks(existing.id).map { it.id }.toSet()
        templateDao.deleteTasks(existing.id)
        templateDao.deleteCategories(existing.id)

        val usedCategoryIds = (templateDao.allCategoryIds() - oldCategoryIds).toMutableSet()
        val usedTaskIds = (templateDao.allTaskIds() - oldTaskIds).toMutableSet()
        val (categories, tasks) = allocateChildren(
            existing.id,
            draft,
            usedCategoryIds,
            usedTaskIds,
        )

        templateDao.updateTemplate(
            existing.copy(
                gameName = draft.gameName,
                templateVersion = draft.templateVersion,
                gameVersionNotes = draft.gameVersionNotes.ifBlank { null },
                zoneId = checkNotNull(draft.zoneOrNull()).id,
                dailyRefreshMinutes = draft.dailyRefresh.hour * 60 + draft.dailyRefresh.minute,
                weeklyRefreshDay = draft.weeklyRefreshDay.value,
                weeklyRefreshMinutes =
                draft.weeklyRefreshTime.hour * 60 + draft.weeklyRefreshTime.minute,
                origin = origin,
                builtInKey = builtInKey,
                updatedAt = now,
            ),
        )
        templateDao.insertCategories(categories)
        if (tasks.isNotEmpty()) templateDao.insertTasks(tasks)
    }

    private fun allocateChildren(
        templateId: String,
        draft: TemplateDraft,
        usedCategoryIds: MutableSet<String>,
        usedTaskIds: MutableSet<String>,
    ): Pair<List<TemplateCategoryEntity>, List<TemplateTaskEntity>> {
        val categories = mutableListOf<TemplateCategoryEntity>()
        val tasks = mutableListOf<TemplateTaskEntity>()
        draft.categories.forEachIndexed { categoryIndex, category ->
            val categoryId = allocateId(category.id, usedCategoryIds)
            categories += TemplateCategoryEntity(
                id = categoryId,
                templateId = templateId,
                name = category.name,
                sortOrder = categoryIndex,
            )
            category.tasks.forEachIndexed { taskIndex, task ->
                tasks += TemplateTaskEntity(
                    id = allocateId(task.id, usedTaskIds),
                    templateId = templateId,
                    categoryId = categoryId,
                    title = task.title,
                    progressType = task.progressType,
                    startValue = task.startDecimal() ?: java.math.BigDecimal.ZERO,
                    targetValue = task.targetDecimal() ?: java.math.BigDecimal.ONE,
                    unit = task.unit.ifBlank { null },
                    recurrence = task.recurrence,
                    weekdays = encodeWeekdays(task.weekdays),
                    everyNDays = if (task.recurrence == com.daigan.record.data.model.RecurrenceType.EVERY_N_DAYS) {
                        task.everyNOrNull()
                    } else {
                        null
                    },
                    sortOrder = taskIndex,
                )
            }
        }
        return categories to tasks
    }

    private fun allocateId(candidate: String, used: MutableSet<String>): String {
        val id = if (candidate.isNotBlank() && used.add(candidate)) {
            candidate
        } else {
            UUID.randomUUID().toString().also { used += it }
        }
        return id
    }

    private fun requireValid(draft: TemplateDraft) {
        val errors = draft.validate()
        require(errors.isEmpty()) { errors.values.first() }
    }

    private suspend fun nextSortOrder(): Int = (templateDao.maxSortOrder() ?: -1) + 1

    private fun now(): Instant = PersistedInstant.normalize(timeProvider.now())
}
