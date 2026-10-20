package com.kuankuan.daigan.data.template

import com.kuankuan.daigan.data.model.ProgressType
import com.kuankuan.daigan.data.model.RecurrenceType
import com.kuankuan.daigan.testing.TemplateFixtures
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class TemplateFileCodecTest {

    private val template = TemplateFixtures.template(
        categories = listOf(
            TemplateFixtures.category(
                templateId = "template-1",
                id = "c-daily",
                name = "日常",
                tasks = listOf(
                    TemplateFixtures.task(
                        templateId = "template-1",
                        categoryId = "c-daily",
                        id = "t-check",
                        title = "每日签到",
                    ),
                    TemplateFixtures.task(
                        templateId = "template-1",
                        categoryId = "c-daily",
                        id = "t-numeric",
                        title = "消耗活性",
                        progressType = ProgressType.NUMERIC,
                        startValue = "0",
                        targetValue = "100.5",
                        unit = "活性",
                    ),
                ),
            ),
            TemplateFixtures.category(
                templateId = "template-1",
                id = "c-week",
                name = "每周",
                tasks = listOf(
                    TemplateFixtures.task(
                        templateId = "template-1",
                        categoryId = "c-week",
                        id = "t-selected",
                        title = "周末副本",
                        recurrence = RecurrenceType.SELECTED_WEEKDAYS,
                        weekdays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                    ),
                    TemplateFixtures.task(
                        templateId = "template-1",
                        categoryId = "c-week",
                        id = "t-interval",
                        title = "隔日清理",
                        recurrence = RecurrenceType.EVERY_N_DAYS,
                        everyNDays = 2,
                    ),
                ),
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

    @Test
    fun monthlyTemplateFilePreservesDay() {
        val category = template.categories.first()
        val monthly = template.copy(categories = listOf(category.copy(tasks = listOf(category.tasks.first().copy(recurrence = RecurrenceType.MONTHLY, monthDay = 31)))))
        val decoded = TemplateFileCodec.decode(TemplateFileCodec.encode(monthly)) as TemplateFileResult.Success
        assertEquals("31", decoded.content.draft.categories.single().tasks.single().monthDay)
        val invalid = monthly.copy(categories = listOf(category.copy(tasks = listOf(category.tasks.first().copy(recurrence = RecurrenceType.MONTHLY, monthDay = 32)))))
        assertTrue(TemplateFileCodec.decode(TemplateFileCodec.encode(invalid)) is TemplateFileResult.Failure)
    }

    private fun encoded(): String = String(TemplateFileCodec.encode(template), Charsets.UTF_8)

    private fun mutateJson(transform: (JsonObject) -> JsonObject): ByteArray {
        val root = Json.parseToJsonElement(encoded()).jsonObject
        return Json.encodeToString(
            JsonObject.serializer(),
            transform(root),
        ).toByteArray(Charsets.UTF_8)
    }

    private fun mutateTemplate(transform: (JsonObject) -> JsonObject): ByteArray =
        mutateJson { root ->
            val templateObject = root["template"]!!.jsonObject
            JsonObject(root + ("template" to transform(templateObject)))
        }

    private fun failure(bytes: ByteArray): String {
        val result = TemplateFileCodec.decode(bytes)
        assertTrue("expected a failure, got $result", result is TemplateFileResult.Failure)
        return (result as TemplateFileResult.Failure).message
    }

    @Test
    fun `round trip keeps every template field and Chinese text`() {
        val result = TemplateFileCodec.decode(TemplateFileCodec.encode(template))
        assertTrue("decode failed: $result", result is TemplateFileResult.Success)
        val content = (result as TemplateFileResult.Success).content
        assertEquals("template-1", content.templateId)
        assertEquals(3, content.categoryCount)
        assertEquals(5, content.taskCount)
        val draft = content.draft
        assertEquals("重返未来：1999", draft.gameName)
        assertEquals("2026.10", draft.templateVersion)
        assertEquals("测试模板", draft.gameVersionNotes)
        assertEquals("Asia/Shanghai", draft.zoneText)
        assertEquals("05:00", "%02d:%02d".format(draft.dailyRefresh.hour, draft.dailyRefresh.minute))
        assertEquals(DayOfWeek.MONDAY, draft.weeklyRefreshDay)
        assertEquals(listOf("日常", "每周", "短期委托"), draft.categories.map { it.name })
        assertEquals(
            listOf("每日签到", "消耗活性"),
            draft.categories[0].tasks.map { it.title },
        )
        val numeric = draft.categories[0].tasks[1]
        assertEquals(ProgressType.NUMERIC, numeric.progressType)
        assertEquals("100.5", numeric.targetValue)
        assertEquals("活性", numeric.unit)
        assertEquals(
            setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            draft.categories[1].tasks[0].weekdays,
        )
        assertEquals("2", draft.categories[1].tasks[1].everyNDays)
        assertEquals(RecurrenceType.ONE_TIME, draft.categories[2].tasks[0].recurrence)
        assertEquals(
            emptyMap<String, String>(),
            draft.validate(),
        )
    }

    @Test
    fun `export contains only template content and format metadata`() {
        val json = encoded()
        assertTrue(json.contains("\"format\": \"daigan-template\""))
        assertTrue(json.contains("\"formatVersion\": 1"))
        listOf("password", "loginAccount", "orders", "receipts", "owners", "accounts", "uid")
            .forEach { forbidden ->
                assertFalse("template file must not contain $forbidden", json.contains(forbidden))
            }
    }

    @Test
    fun `an empty file is rejected`() {
        assertTrue(failure(ByteArray(0)).contains("空"))
    }

    @Test
    fun `a file over the size limit is rejected before parsing`() {
        assertTrue(
            failure(ByteArray(TemplateFileCodec.MAX_FILE_BYTES + 1) { 'a'.code.toByte() })
                .contains("上限"),
        )
    }

    @Test
    fun `invalid UTF-8 and malformed JSON are rejected`() {
        assertTrue(failure(byteArrayOf(0xC3.toByte(), 0x28)).contains("UTF-8"))
        assertTrue(failure("{ not json".toByteArray()).contains("JSON"))
    }

    @Test
    fun `a different file format or version is rejected`() {
        val wrongFormat = mutateJson { root ->
            JsonObject(root + ("format" to JsonPrimitive("another-app-template")))
        }
        assertTrue(failure(wrongFormat).contains("format"))

        val wrongVersion = mutateJson { root ->
            JsonObject(root + ("formatVersion" to JsonPrimitive(2)))
        }
        val message = failure(wrongVersion)
        assertTrue(message.contains("版本"))
        assertTrue(message.contains("2"))
    }

    @Test
    fun `business data outside the template is rejected`() {
        val withOrders = mutateJson { root ->
            JsonObject(root + ("orders" to Json.parseToJsonElement("[]")))
        }
        assertTrue(failure(withOrders).contains("orders"))
    }

    @Test
    fun `duplicate identifiers and broken category references are rejected`() {
        val duplicateId = mutateTemplate { templateObject ->
            val categories = templateObject["categories"]!!.let { element ->
                Json.parseToJsonElement(element.toString())
            }
            val text = categories.toString().replace("\"t-check\"", "\"c-daily\"")
            JsonObject(
                templateObject + ("categories" to Json.parseToJsonElement(text)),
            )
        }
        assertTrue(failure(duplicateId).contains("标识重复"))

        val brokenReference = mutateTemplate { templateObject ->
            val text = templateObject["categories"].toString()
                .replace("\"categoryId\":\"c-once\"", "\"categoryId\":\"c-daily\"")
            JsonObject(
                templateObject + ("categories" to Json.parseToJsonElement(text)),
            )
        }
        assertTrue(failure(brokenReference).contains("categoryId"))
    }

    @Test
    fun `missing required fields are rejected`() {
        val noGameName = mutateTemplate { JsonObject(it - "gameName") }
        assertTrue(failure(noGameName).contains("gameName"))

        val noVersion = mutateTemplate { JsonObject(it - "templateVersion") }
        assertTrue(failure(noVersion).contains("templateVersion"))

        val noTasks = mutateTemplate { templateObject ->
            val text = templateObject["categories"].toString()
                .replace("\"tasks\":", "\"removedTasks\":")
            JsonObject(
                templateObject + ("categories" to Json.parseToJsonElement(text)),
            )
        }
        assertFalse(failure(noTasks).isBlank())
    }

    @Test
    fun `invalid recurrence and numeric values are rejected`() {
        val badRecurrence = mutateTemplate { templateObject ->
            val text = templateObject["categories"].toString()
                .replace("\"recurrence\":\"ONE_TIME\"", "\"recurrence\":\"SOMETIMES\"")
            JsonObject(
                templateObject + ("categories" to Json.parseToJsonElement(text)),
            )
        }
        assertTrue(failure(badRecurrence).contains("重复周期"))

        val targetEqualsStart = mutateTemplate { templateObject ->
            val text = templateObject["categories"].toString()
                .replace("\"targetValue\":\"100.5\"", "\"targetValue\":\"0\"")
            JsonObject(
                templateObject + ("categories" to Json.parseToJsonElement(text)),
            )
        }
        assertTrue(failure(targetEqualsStart).contains("目标值"))

        val tooManyDecimals = mutateTemplate { templateObject ->
            val text = templateObject["categories"].toString()
                .replace("\"targetValue\":\"100.5\"", "\"targetValue\":\"1.2345\"")
            JsonObject(
                templateObject + ("categories" to Json.parseToJsonElement(text)),
            )
        }
        assertTrue(failure(tooManyDecimals).contains("小数"))
    }

    @Test
    fun `unknown fields and empty collections are rejected`() {
        val unknownTemplateField = mutateTemplate { templateObject ->
            JsonObject(templateObject + ("owners" to Json.parseToJsonElement("[]")))
        }
        assertTrue(failure(unknownTemplateField).contains("不支持的字段"))

        val emptyCategories = mutateTemplate { templateObject ->
            JsonObject(templateObject + ("categories" to Json.parseToJsonElement("[]")))
        }
        assertTrue(failure(emptyCategories).contains("分类"))
    }

    @Test
    fun `unknown task fields are rejected instead of silently merged`() {
        val unknownField = mutateTemplate { templateObject ->
            val text = templateObject["categories"].toString()
                .replace("\"title\":\"每日签到\",", "\"title\":\"每日签到\",\"password\":\"x\",")
            JsonObject(
                templateObject + ("categories" to Json.parseToJsonElement(text)),
            )
        }
        assertTrue(failure(unknownField).contains("不支持的字段"))
    }

    @Test
    fun `a null weekly weekday or invalid time is rejected`() {
        val badDay = mutateTemplate { templateObject ->
            JsonObject(templateObject + ("weeklyRefreshDay" to JsonPrimitive("FUNDAY")))
        }
        assertTrue(failure(badDay).contains("星期"))

        val badTime = mutateTemplate { templateObject ->
            JsonObject(templateObject + ("dailyRefresh" to JsonPrimitive("25:00")))
        }
        assertFalse(failure(badTime).isBlank())
    }

    @Test
    fun `a minimal hand written file is accepted`() {
        val minimal = buildJsonObject {
            put("format", TemplateFileCodec.FORMAT_NAME)
            put("formatVersion", TemplateFileCodec.FORMAT_VERSION)
            put(
                "template",
                Json.parseToJsonElement(
                    """
                    {
                      "id": "hand-written",
                      "gameName": "手写模板",
                      "templateVersion": "1.0",
                      "gameVersionNotes": null,
                      "timeZone": "Asia/Tokyo",
                      "dailyRefresh": "04:00",
                      "weeklyRefreshDay": "TUESDAY",
                      "weeklyRefreshTime": "04:00",
                      "categories": [
                        {
                          "id": "c1",
                          "name": "日常",
                          "tasks": [
                            {
                              "id": "t1",
                              "categoryId": "c1",
                              "title": "签到",
                              "progressType": "CHECKBOX",
                              "startValue": "0",
                              "targetValue": "1",
                              "unit": null,
                              "recurrence": "DAILY",
                              "weekdays": [],
                              "everyNDays": null
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )
        }
        val result = TemplateFileCodec.decode(
            Json.encodeToString(JsonObject.serializer(), minimal).toByteArray(),
        )
        assertTrue("minimal decode failed: $result", result is TemplateFileResult.Success)
        val content = (result as TemplateFileResult.Success).content
        assertEquals("手写模板", content.draft.gameName)
        assertEquals(DayOfWeek.TUESDAY, content.draft.weeklyRefreshDay)
    }
}
