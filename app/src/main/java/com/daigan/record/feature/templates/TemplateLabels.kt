package com.daigan.record.feature.templates

import com.daigan.record.data.model.GameTemplate
import com.daigan.record.data.model.GameTemplateSummary
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.TemplateOrigin
import java.time.DayOfWeek
import java.time.LocalTime

fun originLabel(origin: TemplateOrigin): String = when (origin) {
    TemplateOrigin.BUILT_IN -> "内置"
    TemplateOrigin.USER -> "自建"
    TemplateOrigin.IMPORTED -> "导入"
}

fun progressLabel(type: ProgressType): String = when (type) {
    ProgressType.CHECKBOX -> "勾选完成"
    ProgressType.NUMERIC -> "数值进度"
}

fun templateRecurrenceLabel(type: RecurrenceType): String = when (type) {
    RecurrenceType.ONE_TIME -> "一次性（短期单）"
    RecurrenceType.DAILY -> "每日"
    RecurrenceType.WEEKLY -> "每周"
    RecurrenceType.SELECTED_WEEKDAYS -> "指定星期"
    RecurrenceType.EVERY_N_DAYS -> "每隔若干天"
}

fun weekdayShortLabel(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "一"
    DayOfWeek.TUESDAY -> "二"
    DayOfWeek.WEDNESDAY -> "三"
    DayOfWeek.THURSDAY -> "四"
    DayOfWeek.FRIDAY -> "五"
    DayOfWeek.SATURDAY -> "六"
    DayOfWeek.SUNDAY -> "日"
}

fun weekdayLabel(day: DayOfWeek): String = "星期${weekdayShortLabel(day)}"

fun timeText(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

fun refreshSummary(template: GameTemplateSummary): String =
    "${template.refresh.zoneId.id} · 每日 ${timeText(template.refresh.dailyRefresh)} 刷新"

fun refreshSummary(template: GameTemplate): String =
    "${template.refresh.zoneId.id} · 每日 ${timeText(template.refresh.dailyRefresh)} 刷新"

fun taskCountSummary(template: GameTemplateSummary): String =
    "${template.categoryCount} 个分类 · ${template.taskCount} 个任务 " +
        "（托管 ${template.hostingTaskCount} / 短期 ${template.shortTaskCount}）"

/** A file name that is safe on every document provider the picker may return. */
fun templateFileName(gameName: String, templateVersion: String): String {
    val raw = "$gameName-$templateVersion"
    val safe = raw.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-").trim('-')
    return "daigan-template-${safe.ifEmpty { "template" }}.json"
}
