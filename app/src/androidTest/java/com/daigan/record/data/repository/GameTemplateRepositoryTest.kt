package com.daigan.record.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.AccountDraft
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.data.model.TemplateDraft
import com.daigan.record.data.model.TemplateOrderKind
import com.daigan.record.data.model.provenance
import com.daigan.record.data.model.toTaskDraft
import com.daigan.record.data.template.BuiltInTemplates
import com.daigan.record.data.template.TemplateFileContent
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.hostingDraft
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameTemplateRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var env: OrderTestEnvironment

    @Before
    fun setUp() {
        env = OrderTestEnvironment(context)
    }

    @After
    fun tearDown() {
        env.close()
    }

    private suspend fun summaries() = env.templateRepository.findAllSummaries()

    private suspend fun templateByName(name: String) =
        summaries().first { it.gameName == name }.let { env.templateRepository.findTemplate(it.id)!! }

    @Test
    fun builtInsAreInstalledOnceInTheRequiredOrder() = runBlocking {
        env.templateRepository.ensureBuiltInTemplates()

        val first = summaries()
        assertEquals(
            listOf("重返未来：1999", "鸣潮", "通用模板"),
            first.map { it.gameName },
        )
        assertEquals(3, first.size)
        first.forEach { summary ->
            assertEquals(com.daigan.record.data.model.TemplateOrigin.BUILT_IN, summary.origin)
            assertTrue("${summary.gameName} needs hosting tasks", summary.hostingTaskCount > 0)
            assertTrue("${summary.gameName} needs one-time tasks", summary.shortTaskCount > 0)
        }

        // A second initialization is a no-op: no duplicate rows, no rewritten counts.
        env.templateRepository.ensureBuiltInTemplates()
        assertEquals(3, summaries().size)
    }

    @Test
    fun builtInInitializationNeverOverwritesUserEdits() = runBlocking {
        env.templateRepository.ensureBuiltInTemplates()
        val generic = templateByName("通用模板")
        val edited = TemplateDraft.from(generic).copy(
            gameName = "我的通用模板",
            templateVersion = "2.0",
        )
        env.templateRepository.saveTemplate(edited, generic.id)

        env.templateRepository.ensureBuiltInTemplates()

        val reloaded = env.templateRepository.findTemplate(generic.id)!!
        assertEquals("我的通用模板", reloaded.gameName)
        assertEquals("2.0", reloaded.templateVersion)
        assertEquals(3, summaries().size)
        assertEquals(
            "a built-in keeps its origin after the user edits it",
            com.daigan.record.data.model.TemplateOrigin.BUILT_IN,
            reloaded.origin,
        )
    }

    @Test
    fun aDeletedBuiltInIsNotResurrected() = runBlocking {
        env.templateRepository.ensureBuiltInTemplates()
        val generic = templateByName("通用模板")
        env.templateRepository.deleteTemplate(generic.id)

        env.templateRepository.ensureBuiltInTemplates()

        assertEquals(listOf("重返未来：1999", "鸣潮"), summaries().map { it.gameName })
        assertNull(env.templateRepository.findTemplate(generic.id))
    }

    @Test
    fun templateEditsAndIdsSurviveADatabaseReopen() = runBlocking {
        val databaseName = "phase4-template-persistence.db"
        val first = OrderTestEnvironment(context, databaseName = databaseName, inMemory = false)
        try {
            first.templateRepository.ensureBuiltInTemplates()
            val created = TemplateDraft(
                gameName = "中文模板·测试",
                templateVersion = "9.1",
                gameVersionNotes = "重启后仍应存在",
                categories = listOf(
                    com.daigan.record.data.model.TemplateCategoryDraft(
                        id = "persisted-category",
                        name = "日常",
                        tasks = listOf(
                            com.daigan.record.data.model.TemplateTaskDraft(
                                id = "persisted-task",
                                title = "每日签到",
                            ),
                        ),
                    ),
                ),
            )
            val id = first.templateRepository.saveTemplate(created)
            first.close(deleteDatabaseFile = false)

            val second = OrderTestEnvironment(context, databaseName = databaseName, inMemory = false)
            try {
                val reloaded = second.templateRepository.findTemplate(id)
                assertNotNull(reloaded)
                assertEquals("中文模板·测试", reloaded!!.gameName)
                assertEquals("9.1", reloaded.templateVersion)
                assertEquals("重启后仍应存在", reloaded.gameVersionNotes)
                assertEquals("每日签到", reloaded.categories.single().tasks.single().title)
                assertEquals("persisted-category", reloaded.categories.single().id)
                assertEquals("persisted-task", reloaded.categories.single().tasks.single().id)
                assertEquals(4, second.templateRepository.findAllSummaries().size)
            } finally {
                second.close(deleteDatabaseFile = true)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun deletingATemplateClearsTheAccountDefaultButKeepsOrders() = runBlocking {
        env.templateRepository.ensureBuiltInTemplates()
        val template = templateByName("重返未来：1999")
        val owner = env.ownerRepository.createOwner("模板验收")
        val account = env.accountRepository.createAccount(
            owner.id,
            AccountDraft(
                gameName = template.gameName,
                server = "官服",
                uid = "tpl-1",
                loginAccount = "tpl-login",
                loginMethod = LoginMethod.VERIFICATION_CODE,
                defaultTemplateId = template.id,
            ),
        )
        assertEquals(template.id, account.defaultTemplateId)

        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(
                owner.id,
                account.id,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 10),
                tasks = template.tasksFor(TemplateOrderKind.HOSTING).map { it.toTaskDraft() },
            ).copy(sourceTemplate = template.provenance()),
        )
        val before = env.orderRepository.observeOrderDetail(orderId).first()

        env.templateRepository.deleteTemplate(template.id)

        assertNull(env.templateRepository.findTemplate(template.id))
        assertNull(env.accountRepository.findAccount(account.id)!!.defaultTemplateId)
        val after = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(before!!.order, after.order)
        assertEquals(before.tasks, after.tasks)
        assertEquals(before.currentPeriod, after.currentPeriod)
        assertEquals("重返未来：1999", after.order.sourceTemplateName)
        assertEquals(template.templateVersion, after.order.sourceTemplateVersion)
        // The account itself and every order remain usable without a template.
        assertNotNull(env.accountRepository.findAccount(account.id))
    }

    @Test
    fun saveAsAndReplaceResolveASameNameConflictExplicitly() = runBlocking {
        env.templateRepository.ensureBuiltInTemplates()
        val original = templateByName("重返未来：1999")
        val importedDraft = TemplateDraft.from(original).copy(
            gameVersionNotes = "来自文件的版本",
            categories = listOf(
                com.daigan.record.data.model.TemplateCategoryDraft(
                    name = "导入分类",
                    tasks = listOf(
                        com.daigan.record.data.model.TemplateTaskDraft(
                            title = "导入任务",
                            recurrence = com.daigan.record.data.model.RecurrenceType.ONE_TIME,
                        ),
                    ),
                ),
            ),
        )

        val saveAs = env.templateRepository.importTemplate(
            TemplateFileContent(
                templateId = UUID.randomUUID().toString(),
                draft = importedDraft,
                categoryCount = 1,
                taskCount = 1,
            ),
            TemplateImportResolution.SaveAs,
        )
        assertFalse(saveAs.replaced)
        assertEquals("重返未来：1999（导入）", saveAs.gameName)
        assertNotEquals(original.id, saveAs.templateId)
        val saved = env.templateRepository.findTemplate(saveAs.templateId)!!
        assertEquals("来自文件的版本", saved.gameVersionNotes)
        assertEquals("导入任务", saved.categories.first().tasks.first().title)
        assertNotEquals(
            original.categories.first().id,
            saved.categories.first().id,
        )

        val replaced = env.templateRepository.importTemplate(
            TemplateFileContent(
                templateId = UUID.randomUUID().toString(),
                draft = importedDraft.copy(gameName = "重返未来：1999"),
                categoryCount = 1,
                taskCount = 1,
            ),
            TemplateImportResolution.Replace(original.id),
        )
        assertTrue(replaced.replaced)
        assertEquals(original.id, replaced.templateId)
        val afterReplace = env.templateRepository.findTemplate(original.id)!!
        assertEquals("导入任务", afterReplace.categories.first().tasks.first().title)
        assertEquals("来自文件的版本", afterReplace.gameVersionNotes)
        // The save-as entry is untouched by replacing the original.
        assertEquals("导入任务", env.templateRepository.findTemplate(saveAs.templateId)!!
            .categories.first().tasks.first().title)
        assertEquals(4, summaries().size)
    }

    @Test
    fun anInvalidImportWritesNothing() = runBlocking {
        env.templateRepository.ensureBuiltInTemplates()
        val before = summaries()
        val original = templateByName("通用模板")
        val invalid = TemplateDraft.from(original).copy(
            categories = listOf(
                com.daigan.record.data.model.TemplateCategoryDraft(
                    name = "资源",
                    tasks = listOf(
                        com.daigan.record.data.model.TemplateTaskDraft(
                            title = "累计",
                            progressType = com.daigan.record.data.model.ProgressType.NUMERIC,
                            startValue = "10",
                            targetValue = "5",
                        ),
                    ),
                ),
            ),
        )

        val failed = runCatching {
            env.templateRepository.importTemplate(
                TemplateFileContent("invalid", invalid, 1, 1),
                TemplateImportResolution.SaveAs,
            )
        }
        assertTrue(failed.isFailure)

        val missingTarget = runCatching {
            env.templateRepository.importTemplate(
                TemplateFileContent(
                    UUID.randomUUID().toString(),
                    invalid.copy(
                        categories = listOf(
                            com.daigan.record.data.model.TemplateCategoryDraft(
                                name = "日常",
                                tasks = listOf(
                                    com.daigan.record.data.model.TemplateTaskDraft(title = "签到"),
                                ),
                            ),
                        ),
                    ),
                    1,
                    1,
                ),
                TemplateImportResolution.Replace("no-such-template"),
            )
        }
        assertTrue(missingTarget.isFailure)

        assertEquals(before, summaries())
        assertEquals(
            original.categories.first().tasks.first().title,
            env.templateRepository.findTemplate(original.id)!!
                .categories.first().tasks.first().title,
        )
    }

    @Test
    fun savingRemapsIdsThatWouldCollideWithAnotherTemplate() = runBlocking {
        val a = env.templateRepository.saveTemplate(
            TemplateDraft(
                gameName = "模板A",
                categories = listOf(
                    com.daigan.record.data.model.TemplateCategoryDraft(
                        id = "shared-category",
                        name = "日常",
                        tasks = listOf(
                            com.daigan.record.data.model.TemplateTaskDraft(
                                id = "shared-task",
                                title = "签到A",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val b = env.templateRepository.saveTemplate(
            TemplateDraft(
                gameName = "模板B",
                categories = listOf(
                    com.daigan.record.data.model.TemplateCategoryDraft(
                        id = "shared-category",
                        name = "日常",
                        tasks = listOf(
                            com.daigan.record.data.model.TemplateTaskDraft(
                                id = "shared-task",
                                title = "签到B",
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertNotEquals(a, b)
        val savedA = env.templateRepository.findTemplate(a)!!
        val savedB = env.templateRepository.findTemplate(b)!!
        assertNotEquals(
            savedA.categories.single().id,
            savedB.categories.single().id,
        )
        assertNotEquals(
            savedA.categories.single().tasks.single().id,
            savedB.categories.single().tasks.single().id,
        )
        assertEquals("签到A", savedA.categories.single().tasks.single().title)
        assertEquals("签到B", savedB.categories.single().tasks.single().title)
    }

    @Test
    fun builtInAssetKeysMatchTheSeedDescriptors() = runBlocking {
        env.templateRepository.ensureBuiltInTemplates()
        val keys = summaryKeys()
        assertEquals(
            BuiltInTemplates.ALL.map { it.seedKey }.toSet(),
            keys,
        )
    }

    private suspend fun summaryKeys(): Set<String> = env.database.templateDao()
        .findAllTemplates()
        .mapNotNull { it.builtInKey }
        .toSet()
}
