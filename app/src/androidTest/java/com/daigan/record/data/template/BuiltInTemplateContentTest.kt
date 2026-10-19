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
 * The generic starter asset must parse with the public codec and expose both order kinds.
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
        val expectedNames = listOf("通用模板")
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
    fun theGenericTemplateContainsShortTasks() = runBlocking {
        val titles = BuiltInTemplates.ALL.map { descriptor ->
            content(descriptor).draft.categories
                .flatMap { it.tasks }
                .filter { it.recurrence == RecurrenceType.ONE_TIME }
                .map { it.title }
        }
        assertTrue(titles.single().any { it.isNotBlank() })
    }

}
