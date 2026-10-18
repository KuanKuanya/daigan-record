package com.daigan.record.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.local.toCore
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.TemplateDraft
import com.daigan.record.data.model.TemplateOrderKind
import com.daigan.record.data.model.provenance
import com.daigan.record.data.model.toTaskDraft
import com.daigan.record.data.repository.TemplateImportResolution
import com.daigan.record.data.template.TemplateFileContent
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.hostingDraft
import com.daigan.record.testing.shortDraft
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Template selection creates real work, and later template changes never rewrite an
 * order's own snapshot. All three built-in templates are exercised for both order types.
 */
@RunWith(AndroidJUnit4::class)
class TemplateOrderSnapshotTest {

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

    @Test
    fun everyBuiltInTemplateCreatesBothOrderTypesWithItsOwnDefaults() = runBlocking {
        env.templateRepository.ensureBuiltInTemplates()
        val (owner, account) = env.seedOwnerAndAccount()
        val summaries = env.templateRepository.findAllSummaries()
        assertEquals(3, summaries.size)

        summaries.forEach { summary ->
            val template = env.templateRepository.findTemplate(summary.id)!!
            val hostingTasks = template.tasksFor(TemplateOrderKind.HOSTING)
            val shortTasks = template.tasksFor(TemplateOrderKind.SHORT_TASK)
            assertTrue("${summary.gameName} hosting tasks", hostingTasks.isNotEmpty())
            assertTrue("${summary.gameName} short tasks", shortTasks.isNotEmpty())

            val hostingOrderId = env.orderRepository.createHostingOrder(
                hostingDraft(
                    owner.id,
                    account.id,
                    LocalDate.of(2026, 10, 1),
                    LocalDate.of(2026, 10, 31),
                    tasks = hostingTasks.map { it.toTaskDraft() },
                    rules = template.refresh.toCore(),
                ).copy(sourceTemplate = template.provenance(), creationOperationId = UUID.randomUUID().toString()),
            )
            val hostingDetail = env.orderRepository.observeOrderDetail(hostingOrderId).first()!!
            assertEquals(hostingTasks.map { it.title }, hostingDetail.tasks.map { it.title })
            assertEquals(template.refresh.zoneId.id, hostingDetail.order.refresh.zoneId.id)
            assertEquals(
                template.refresh.dailyRefresh,
                hostingDetail.order.refresh.dailyRefresh,
            )
            assertEquals(
                template.refresh.weeklyRefreshTime,
                hostingDetail.order.refresh.weeklyRefreshTime,
            )
            assertEquals(summary.id, hostingDetail.order.sourceTemplateId)
            assertEquals(summary.gameName, hostingDetail.order.sourceTemplateName)
            assertEquals(summary.templateVersion, hostingDetail.order.sourceTemplateVersion)
            // Numeric defaults survive exactly as written in the template.
            hostingTasks.filter { it.progressType == ProgressType.NUMERIC }.forEach { templateTask ->
                val persisted = hostingDetail.tasks.first { it.title == templateTask.title }
                assertEquals(templateTask.startValue, persisted.startValue)
                assertEquals(templateTask.targetValue, persisted.targetValue)
                assertEquals(templateTask.unit, persisted.unit)
            }
            // Completion records can be written against the created template tasks.
            val today = hostingDetail.currentHostingTasks()
            assertTrue("${summary.gameName} should have actionable work", today.isNotEmpty())
            env.orderRepository.setOccurrenceCompleted(
                today.first().task.id,
                true,
                today.first().slot.key,
            )
            assertTrue(
                env.orderRepository.observeOrderDetail(hostingOrderId).first()!!
                    .occurrences.any { it.completedAt != null },
            )

            val shortOrderId = env.orderRepository.createShortOrder(
                shortDraft(
                    owner.id,
                    account.id,
                    tasks = shortTasks.map { it.toTaskDraft() },
                ).copy(sourceTemplate = template.provenance(), creationOperationId = UUID.randomUUID().toString()),
            )
            val shortDetail = env.orderRepository.observeOrderDetail(shortOrderId).first()!!
            assertEquals(OrderType.SHORT_TASK, shortDetail.order.type)
            assertEquals(shortTasks.map { it.title }, shortDetail.tasks.map { it.title })
            shortDetail.tasks.forEach {
                assertEquals(
                    "one-time template tasks stay one-time in short orders",
                    RecurrenceType.ONE_TIME,
                    it.recurrence,
                )
            }
            env.orderRepository.setShortTaskCompleted(shortDetail.tasks.first().id, true)
            assertTrue(
                env.orderRepository.observeOrderDetail(shortOrderId).first()!!
                    .tasks.first().completed,
            )
        }
    }

    @Test
    fun editingReplacingAndDeletingATemplateNeverChangesAnExistingOrder() = runBlocking {
        env.templateRepository.ensureBuiltInTemplates()
        val (owner, account) = env.seedOwnerAndAccount()
        val summary = env.templateRepository.findAllSummaries().first()
        val template = env.templateRepository.findTemplate(summary.id)!!

        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(
                owner.id,
                account.id,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31),
                tasks = template.tasksFor(TemplateOrderKind.HOSTING).map { it.toTaskDraft() },
            ).copy(sourceTemplate = template.provenance(), creationOperationId = UUID.randomUUID().toString()),
        )
        val shortId = env.orderRepository.createShortOrder(
            shortDraft(
                owner.id,
                account.id,
                tasks = template.tasksFor(TemplateOrderKind.SHORT_TASK).map { it.toTaskDraft() },
            ).copy(sourceTemplate = template.provenance(), creationOperationId = UUID.randomUUID().toString()),
        )
        val before = env.orderRepository.observeOrderDetail(orderId).first()!!
        val beforeShort = env.orderRepository.observeOrderDetail(shortId).first()!!

        // Edit the template: rename, change numeric targets, remove a category.
        val edited = TemplateDraft.from(template).copy(
            gameName = "改名后的模板",
            templateVersion = "99.9",
            categories = TemplateDraft.from(template).categories.take(1).map { category ->
                category.copy(
                    name = "改名分类",
                    tasks = category.tasks.map { task ->
                        task.copy(title = "改名任务", targetValue = "999")
                    },
                )
            },
        )
        env.templateRepository.saveTemplate(edited, template.id)

        val afterEdit = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(before.order, afterEdit.order)
        assertEquals(before.tasks, afterEdit.tasks)
        assertEquals(before.periods, afterEdit.periods)
        assertEquals(before.occurrences, afterEdit.occurrences)
        assertEquals(before.receipts, afterEdit.receipts)

        // Import over the same template with completely different content.
        env.templateRepository.importTemplate(
            TemplateFileContent(
                templateId = UUID.randomUUID().toString(),
                draft = TemplateDraft(
                    gameName = "改名后的模板",
                    templateVersion = "100.0",
                    categories = listOf(
                        com.daigan.record.data.model.TemplateCategoryDraft(
                            name = "替换分类",
                            tasks = listOf(
                                com.daigan.record.data.model.TemplateTaskDraft(
                                    title = "替换任务",
                                    recurrence = RecurrenceType.DAILY,
                                ),
                            ),
                        ),
                    ),
                ),
                categoryCount = 1,
                taskCount = 1,
            ),
            TemplateImportResolution.Replace(template.id),
        )
        val afterReplace = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(before.tasks, afterReplace.tasks)
        assertEquals(before.periods, afterReplace.periods)
        assertEquals(
            "the historical provenance label is not rewritten by an import",
            template.gameName,
            afterReplace.order.sourceTemplateName,
        )
        assertEquals(
            "the historical template version is not rewritten by an import",
            template.templateVersion,
            afterReplace.order.sourceTemplateVersion,
        )

        // Delete the template entirely: orders, work and financial reads stay available.
        env.templateRepository.deleteTemplate(template.id)
        val afterDelete = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(before.tasks, afterDelete.tasks)
        assertEquals(before.periods, afterDelete.periods)
        assertEquals(before.receipts, afterDelete.receipts)
        assertEquals(beforeShort.tasks, env.orderRepository.observeOrderDetail(shortId).first()!!.tasks)
        assertNotNull(env.accountRepository.findAccount(account.id))
        assertTrue(
            env.orderRepository.observeTodayHosting().first().any { it.order.id == orderId },
        )
        assertTrue(
            env.orderRepository.observeTodayShort().first().any { it.order.id == shortId },
        )
    }

    @Test
    fun legacyAccountsAndOrdersStayUsableWithoutATemplate() = runBlocking {
        // No ensureBuiltInTemplates() call here: a legacy install has no template library.
        val (owner, account) = env.seedOwnerAndAccount(nickname = "旧数据老板", gameName = "旧游戏")
        assertNull(account.defaultTemplateId)
        assertTrue(env.templateRepository.findAllSummaries().isEmpty())

        val hostingId = env.orderRepository.createHostingOrder(
            hostingDraft(
                owner.id,
                account.id,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31),
            ),
        )
        val shortId = env.orderRepository.createShortOrder(
            shortDraft(owner.id, account.id),
        )
        val hosting = env.orderRepository.observeOrderDetail(hostingId).first()!!
        val short = env.orderRepository.observeOrderDetail(shortId).first()!!
        assertNull(hosting.order.sourceTemplateId)
        assertNull(short.order.sourceTemplateId)
        assertTrue(env.orderRepository.observeTodayHosting().first().any { it.order.id == hostingId })
        assertTrue(env.orderRepository.observeTodayShort().first().any { it.order.id == shortId })

        // Even after built-ins are installed later, the legacy account still needs no
        // template and its existing orders keep their original snapshots.
        val beforeTasks = hosting.tasks
        env.templateRepository.ensureBuiltInTemplates()
        val after = env.orderRepository.observeOrderDetail(hostingId).first()!!
        assertEquals(beforeTasks, after.tasks)
        assertNull(after.order.sourceTemplateId)
        assertNull(env.accountRepository.findAccount(account.id)!!.defaultTemplateId)
    }
}
