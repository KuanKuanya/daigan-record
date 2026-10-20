package com.kuankuan.daigan.data.model

import com.kuankuan.daigan.data.template.TemplateNames
import com.kuankuan.daigan.testing.TemplateFixtures
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateDraftTest {

    @Test
    fun `monthly templates validate fixed day and clear stale day after recurrence changes`() {
        fun monthly(day: String) = draft(categories = listOf(TemplateCategoryDraft(
            name = "每月", tasks = listOf(TemplateTaskDraft(title = "月常", recurrence = RecurrenceType.MONTHLY, monthDay = day)),
        )))
        for (day in listOf("", "0", "32", "1.5")) assertTrue(monthly(day).validate().containsKey("category.0.task.0.monthDay"))
        for (day in listOf("1", "29", "30", "31")) assertTrue(monthly(day).validate().isEmpty())
        assertEquals("", TemplateTaskDraft(monthDay = "31").normalized().monthDay)
    }

    private fun draft(
        categories: List<TemplateCategoryDraft> = listOf(
            TemplateCategoryDraft(
                id = "category-1",
                name = "日常",
                tasks = listOf(TemplateTaskDraft(id = "task-1", title = "每日签到")),
            ),
        ),
    ) = TemplateDraft(
        gameName = "重返未来：1999",
        templateVersion = "2026.10",
        zoneText = "Asia/Shanghai",
        dailyRefresh = LocalTime.of(5, 0),
        weeklyRefreshDay = DayOfWeek.MONDAY,
        weeklyRefreshTime = LocalTime.of(5, 0),
        categories = categories,
    )

    @Test
    fun `a complete draft has no validation errors`() {
        assertEquals(emptyMap<String, String>(), draft().validate())
    }

    @Test
    fun `required identity fields and the timezone are validated`() {
        val errors = draft().copy(
            gameName = "   ",
            templateVersion = "",
            zoneText = "Mars/Olympus",
        ).validate()
        assertTrue(errors.containsKey("gameName"))
        assertTrue(errors.containsKey("templateVersion"))
        assertTrue(errors.containsKey("zone"))
    }

    @Test
    fun `empty categories or an empty category are rejected`() {
        val noCategories = draft(categories = emptyList()).validate()
        assertTrue(noCategories.containsKey("categories"))

        val emptyCategory = draft(
            categories = listOf(TemplateCategoryDraft(id = "c1", name = "日常", tasks = emptyList())),
        ).validate()
        assertTrue(emptyCategory.containsKey("category.0.tasks"))
    }

    @Test
    fun `numeric progress targets must be greater than the start value`() {
        val invalid = draft(
            categories = listOf(
                TemplateCategoryDraft(
                    id = "c1",
                    name = "资源",
                    tasks = listOf(
                        TemplateTaskDraft(
                            id = "t1",
                            title = "累计",
                            progressType = ProgressType.NUMERIC,
                            startValue = "100",
                            targetValue = "100",
                            unit = "抽",
                        ),
                    ),
                ),
            ),
        ).validate()
        assertTrue(invalid.containsKey("category.0.task.0.target"))
    }

    @Test
    fun `numeric values with more than three decimals are rejected`() {
        val errors = draft(
            categories = listOf(
                TemplateCategoryDraft(
                    id = "c1",
                    name = "资源",
                    tasks = listOf(
                        TemplateTaskDraft(
                            id = "t1",
                            title = "累计",
                            progressType = ProgressType.NUMERIC,
                            startValue = "0",
                            targetValue = "1.2345",
                        ),
                    ),
                ),
            ),
        ).validate()
        assertTrue(errors.containsKey("category.0.task.0.target"))
    }

    @Test
    fun `selected weekdays require at least one day`() {
        val errors = draft(
            categories = listOf(
                TemplateCategoryDraft(
                    id = "c1",
                    name = "每周",
                    tasks = listOf(
                        TemplateTaskDraft(
                            id = "t1",
                            title = "周末副本",
                            recurrence = RecurrenceType.SELECTED_WEEKDAYS,
                            weekdays = emptySet(),
                        ),
                    ),
                ),
            ),
        ).validate()
        assertTrue(errors.containsKey("category.0.task.0.weekdays"))
    }

    @Test
    fun `every-N-days accepts one to three hundred sixty five only`() {
        fun errorsFor(raw: String) = draft(
            categories = listOf(
                TemplateCategoryDraft(
                    id = "c1",
                    name = "周期",
                    tasks = listOf(
                        TemplateTaskDraft(
                            id = "t1",
                            title = "隔日清理",
                            recurrence = RecurrenceType.EVERY_N_DAYS,
                            everyNDays = raw,
                        ),
                    ),
                ),
            ),
        ).validate()

        assertTrue(errorsFor("0").containsKey("category.0.task.0.interval"))
        assertTrue(errorsFor("366").containsKey("category.0.task.0.interval"))
        assertTrue(errorsFor("abc").containsKey("category.0.task.0.interval"))
        assertFalse(errorsFor("2").containsKey("category.0.task.0.interval"))
    }

    @Test
    fun `collection limits are enforced`() {
        val manyCategories = (1..TemplateDraft.MAX_CATEGORIES + 1).map { index ->
            TemplateCategoryDraft(
                id = "c$index",
                name = "分类$index",
                tasks = listOf(TemplateTaskDraft(id = "t$index", title = "任务$index")),
            )
        }
        assertTrue(draft(categories = manyCategories).validate().containsKey("categories"))
    }

    @Test
    fun `one-time tasks are short-order applicability, never hosting recurrence`() {
        val template = TemplateFixtures.template(
            categories = listOf(
                TemplateFixtures.category(
                    templateId = "template-1",
                    id = "c-daily",
                    name = "日常",
                ),
                TemplateFixtures.category(
                    templateId = "template-1",
                    id = "c-once",
                    name = "短期委托",
                    tasks = listOf(
                        TemplateFixtures.task(
                            templateId = "template-1",
                            categoryId = "c-once",
                            id = "t-once",
                            title = "推进主线",
                            recurrence = RecurrenceType.ONE_TIME,
                        ),
                    ),
                ),
            ),
        )

        assertTrue(template.supports(TemplateOrderKind.HOSTING))
        assertTrue(template.supports(TemplateOrderKind.SHORT_TASK))
        assertTrue(template.tasksFor(TemplateOrderKind.HOSTING).none { it.oneTime })
        assertEquals(
            listOf("推进主线"),
            template.tasksFor(TemplateOrderKind.SHORT_TASK).map { it.title },
        )
        assertEquals(
            emptyMap<String, String>(),
            TemplateDraft.from(template).validate(),
        )
    }

    @Test
    fun `a hosting draft rejects a one-time task instead of mapping it to daily`() {
        val draft = HostingOrderDraft(
            ownerId = "owner-1",
            accountId = "account-1",
            startDate = LocalDate.of(2026, 10, 1),
            endDate = LocalDate.of(2026, 10, 10),
            priceYuan = "100.00",
            tasks = listOf(
                TaskDraft(title = "推进主线", recurrence = RecurrenceType.ONE_TIME),
            ),
        )
        val errors = draft.validate()
        assertTrue(errors.containsKey("task.0.recurrence"))

        val short = ShortOrderDraft(
            ownerId = "owner-1",
            accountId = "account-1",
            priceYuan = "100.00",
            tasks = listOf(
                TaskDraft(title = "推进主线", recurrence = RecurrenceType.ONE_TIME),
            ),
        )
        assertFalse(short.validate().containsKey("task.0.recurrence"))
    }

    @Test
    fun `save-as names never collide with the original`() {
        assertEquals("鸣潮", TemplateNames.uniqueGameName("鸣潮", listOf("重返未来：1999")))
        assertEquals("鸣潮（导入）", TemplateNames.uniqueGameName("鸣潮", listOf("鸣潮")))
        assertEquals(
            "鸣潮（导入 2）",
            TemplateNames.uniqueGameName("鸣潮", listOf("鸣潮", "鸣潮（导入）")),
        )
        val truncated = TemplateNames.uniqueGameName(
            base = "游".repeat(40),
            existingNames = listOf("游".repeat(40)),
        )
        assertTrue(truncated.length <= 40)
        assertNotNull(TemplateNames.normalize(truncated))
        assertFalse(TemplateNames.normalize(truncated) == TemplateNames.normalize("游".repeat(40)))
    }
}
