package com.daigan.record.data.local

import com.daigan.record.data.local.entity.GameTemplateEntity
import com.daigan.record.data.local.entity.TemplateCategoryEntity
import com.daigan.record.data.local.entity.TemplateTaskEntity
import com.daigan.record.data.model.GameRefreshRulesDomain
import com.daigan.record.data.model.GameTemplate
import com.daigan.record.data.model.GameTemplateSummary
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.TemplateCategory
import com.daigan.record.data.model.TemplateTask
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

fun GameTemplateEntity.refreshDomain(): GameRefreshRulesDomain = GameRefreshRulesDomain(
    zoneId = ZoneId.of(zoneId),
    dailyRefresh = LocalTime.of(dailyRefreshMinutes / 60, dailyRefreshMinutes % 60),
    weeklyRefreshDay = DayOfWeek.of(weeklyRefreshDay.coerceIn(1, 7)),
    weeklyRefreshTime = LocalTime.of(weeklyRefreshMinutes / 60, weeklyRefreshMinutes % 60),
)

fun TemplateTaskEntity.toModel(): TemplateTask = TemplateTask(
    id = id,
    templateId = templateId,
    categoryId = categoryId,
    title = title,
    progressType = progressType,
    startValue = startValue,
    targetValue = targetValue,
    unit = unit,
    recurrence = recurrence,
    weekdays = parseWeekdays(weekdays),
    everyNDays = everyNDays,
    sortOrder = sortOrder,
)

fun GameTemplateEntity.toModel(
    categories: List<TemplateCategoryEntity>,
    tasks: List<TemplateTaskEntity>,
): GameTemplate {
    val tasksByCategory = tasks.groupBy { it.categoryId }
    return GameTemplate(
        id = id,
        gameName = gameName,
        templateVersion = templateVersion,
        gameVersionNotes = gameVersionNotes,
        refresh = refreshDomain(),
        origin = origin,
        builtInKey = builtInKey,
        createdAt = createdAt,
        updatedAt = updatedAt,
        categories = categories
            .sortedBy { it.sortOrder }
            .map { category ->
                TemplateCategory(
                    id = category.id,
                    templateId = category.templateId,
                    name = category.name,
                    sortOrder = category.sortOrder,
                    tasks = tasksByCategory[category.id].orEmpty()
                        .sortedBy { it.sortOrder }
                        .map(TemplateTaskEntity::toModel),
                )
            },
        sortOrder = sortOrder,
    )
}

fun GameTemplateEntity.toSummary(
    categories: List<TemplateCategoryEntity>,
    tasks: List<TemplateTaskEntity>,
): GameTemplateSummary {
    val categoryIds = categories.map { it.id }.toSet()
    val templateTasks = tasks.filter { it.categoryId in categoryIds }
    return GameTemplateSummary(
        id = id,
        gameName = gameName,
        templateVersion = templateVersion,
        gameVersionNotes = gameVersionNotes,
        refresh = refreshDomain(),
        origin = origin,
        categoryCount = categories.size,
        taskCount = templateTasks.size,
        hostingTaskCount = templateTasks.count { it.recurrence != RecurrenceType.ONE_TIME },
        shortTaskCount = templateTasks.count { it.recurrence == RecurrenceType.ONE_TIME },
        sortOrder = sortOrder,
    )
}
