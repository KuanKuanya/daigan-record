package com.kuankuan.daigan.testing

import com.kuankuan.daigan.data.model.GameRefreshRulesDomain
import com.kuankuan.daigan.data.model.GameTemplate
import com.kuankuan.daigan.data.model.ProgressType
import com.kuankuan.daigan.data.model.RecurrenceType
import com.kuankuan.daigan.data.model.TemplateCategory
import com.kuankuan.daigan.data.model.TemplateOrigin
import com.kuankuan.daigan.data.model.TemplateTask
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

object TemplateFixtures {

    fun template(
        id: String = "template-1",
        gameName: String = "重返未来：1999",
        templateVersion: String = "2026.10",
        notes: String? = "测试模板",
        origin: TemplateOrigin = TemplateOrigin.USER,
        builtInKey: String? = null,
        zone: String = "Asia/Shanghai",
        dailyRefresh: LocalTime = LocalTime.of(5, 0),
        weeklyDay: DayOfWeek = DayOfWeek.MONDAY,
        weeklyTime: LocalTime = LocalTime.of(5, 0),
        categories: List<TemplateCategory> = listOf(
            category(templateId = id, id = "category-1", name = "日常"),
        ),
    ): GameTemplate = GameTemplate(
        id = id,
        gameName = gameName,
        templateVersion = templateVersion,
        gameVersionNotes = notes,
        refresh = GameRefreshRulesDomain(
            zoneId = ZoneId.of(zone),
            dailyRefresh = dailyRefresh,
            weeklyRefreshDay = weeklyDay,
            weeklyRefreshTime = weeklyTime,
        ),
        origin = origin,
        builtInKey = builtInKey,
        createdAt = fixedInstant,
        updatedAt = fixedInstant,
        categories = categories,
    )

    fun category(
        templateId: String,
        id: String,
        name: String,
        tasks: List<TemplateTask>? = null,
    ): TemplateCategory = TemplateCategory(
        id = id,
        templateId = templateId,
        name = name,
        sortOrder = 0,
        tasks = tasks ?: listOf(
            task(
                templateId = templateId,
                categoryId = id,
                id = "$id-task-1",
                title = "每日签到",
                recurrence = RecurrenceType.DAILY,
            ),
        ),
    )

    fun task(
        templateId: String,
        categoryId: String,
        id: String,
        title: String,
        progressType: ProgressType = ProgressType.CHECKBOX,
        startValue: String = "0",
        targetValue: String = "1",
        unit: String? = null,
        recurrence: RecurrenceType = RecurrenceType.DAILY,
        weekdays: Set<DayOfWeek> = emptySet(),
        everyNDays: Int? = null,
        sortOrder: Int = 0,
    ): TemplateTask = TemplateTask(
        id = id,
        templateId = templateId,
        categoryId = categoryId,
        title = title,
        progressType = progressType,
        startValue = BigDecimal(startValue),
        targetValue = BigDecimal(targetValue),
        unit = unit,
        recurrence = recurrence,
        weekdays = weekdays,
        everyNDays = everyNDays,
        sortOrder = sortOrder,
    )
}
