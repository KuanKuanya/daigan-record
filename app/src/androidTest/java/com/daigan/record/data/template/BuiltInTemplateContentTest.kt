package com.daigan.record.data.template

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.daigan.record.data.model.RecurrenceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shipped assets are real product content: they must parse with the public codec,
 * use the requested preparation order, and expose both hosting and short-order tasks.
 * Any unverified detail is labelled in the template notes, not presented as official.
 */
@RunWith(AndroidJUnit4::class)
class BuiltInTemplateContentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source = AssetBuiltInTemplateSource(context)

    private suspend fun content(descriptor: BuiltInTemplateDescriptor): TemplateFileContent {
        val bytes = source.read(descriptor.assetPath)
        val result = TemplateFileCodec.decode(bytes)
        assertTrue("${descriptor.seedKey} must decode: $result", result is TemplateFileResult.Success)
        return (result as TemplateFileResult.Success).content
    }

    @Test
    fun everyBuiltInAssetParsesAndHasBothOrderKinds() = runBlocking {
        val expectedNames = listOf("重返未来：1999", "鸣潮", "通用模板")
        val actualNames = BuiltInTemplates.ALL.map { content(it).draft.gameName }
        assertEquals(expectedNames, actualNames)

        BuiltInTemplates.ALL.forEach { descriptor ->
            val file = content(descriptor)
            val draft = file.draft
            assertTrue(draft.categories.isNotEmpty())
            assertNotNull(draft.gameVersionNotes)
            assertTrue(
                "notes must disclose the evidence level",
                draft.gameVersionNotes!!.isNotBlank(),
            )
            assertTrue(draft.hostingTaskCount > 0)
            assertTrue(draft.shortTaskCount > 0)
            draft.categories.flatMap { it.tasks }.forEach { task ->
                assertTrue(task.title.isNotBlank())
                if (task.recurrence == RecurrenceType.SELECTED_WEEKDAYS) {
                    assertTrue(task.weekdays.isNotEmpty())
                }
                if (task.recurrence == RecurrenceType.EVERY_N_DAYS) {
                    assertNotNull(task.everyNOrNull())
                }
            }
            val hostingTasks = draft.categories.flatMap { it.tasks }
                .filter { it.recurrence != RecurrenceType.ONE_TIME }
            assertEquals(file.taskCount, draft.categories.sumOf { it.tasks.size })
            assertTrue(hostingTasks.isNotEmpty())
        }
    }

    @Test
    fun theGenericTemplateExercisesEverySupportedRecurrence() = runBlocking {
        val generic = content(BuiltInTemplates.ALL.first { it.seedKey == BuiltInTemplates.GENERIC_KEY })
        val recurrences = generic.draft.categories.flatMap { it.tasks }.map { it.recurrence }.toSet()
        assertEquals(
            setOf(
                RecurrenceType.DAILY,
                RecurrenceType.WEEKLY,
                RecurrenceType.SELECTED_WEEKDAYS,
                RecurrenceType.EVERY_N_DAYS,
                RecurrenceType.ONE_TIME,
            ),
            recurrences,
        )
        assertTrue(generic.draft.shortTaskCount >= 1)
    }

    @Test
    fun verifiedRefreshTimesAreShippedForTheTwoNamedGames() = runBlocking {
        val reverse = content(BuiltInTemplates.ALL.first {
            it.seedKey == BuiltInTemplates.REVERSE_1999_KEY
        }).draft
        assertEquals("Asia/Shanghai", reverse.zoneText)
        assertEquals("05:00", "%02d:%02d".format(reverse.dailyRefresh.hour, reverse.dailyRefresh.minute))
        assertEquals("05:00", "%02d:%02d".format(reverse.weeklyRefreshTime.hour, reverse.weeklyRefreshTime.minute))

        val wuwa = content(BuiltInTemplates.ALL.first {
            it.seedKey == BuiltInTemplates.WUTHERING_WAVES_KEY
        }).draft
        assertEquals("Asia/Shanghai", wuwa.zoneText)
        assertEquals("04:00", "%02d:%02d".format(wuwa.dailyRefresh.hour, wuwa.dailyRefresh.minute))
        assertEquals("04:00", "%02d:%02d".format(wuwa.weeklyRefreshTime.hour, wuwa.weeklyRefreshTime.minute))
    }

    @Test
    fun assetsContainNoBusinessData() = runBlocking {
        BuiltInTemplates.ALL.forEach { descriptor ->
            val text = String(source.read(descriptor.assetPath), Charsets.UTF_8)
            listOf("password", "loginAccount", "receipts", "orders", "owners", "uid")
                .forEach { forbidden ->
                    assertFalse(
                        "${descriptor.seedKey} must not contain $forbidden",
                        text.contains(forbidden),
                    )
                }
        }
    }

    @Test
    fun theExpectedShortTaskTitleIsPresentForEachBuiltIn() = runBlocking {
        val titles = BuiltInTemplates.ALL.map { descriptor ->
            content(descriptor).draft.categories
                .flatMap { it.tasks }
                .filter { it.recurrence == RecurrenceType.ONE_TIME }
                .map { it.title }
        }
        titles.forEach { assertTrue(it.isNotEmpty()) }
        assertTrue(titles[0].any { it.contains("主线") })
        assertTrue(titles[1].any { it.contains("主线") })
        assertTrue(titles[2].any { it.contains("委托") })
    }

}
