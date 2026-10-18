package com.daigan.record.feature.orders

import androidx.lifecycle.SavedStateHandle
import com.daigan.record.data.model.HostingOrderDraft
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.ShortOrderDraft
import com.daigan.record.data.repository.OrderRepository
import com.daigan.record.testing.FakeGameAccountRepository
import com.daigan.record.testing.FakeGameTemplateRepository
import com.daigan.record.testing.FakeOrderRepository
import com.daigan.record.testing.FakeOwnerRepository
import com.daigan.record.testing.FixedTimeProvider
import com.daigan.record.testing.MainDispatcherRule
import com.daigan.record.testing.TemplateFixtures
import com.daigan.record.testing.account
import com.daigan.record.testing.owner
import com.daigan.record.ui.navigation.Routes
import java.time.LocalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Template selection fills creation defaults only. These tests pin the contract that a
 * one-time task never becomes a daily hosting occurrence, that an edited task list is not
 * silently replaced by a type switch, and that provenance is captured at creation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewOrderTemplateDefaultsTest {

    @get:Rule
    val main = MainDispatcherRule()

    private class RecordingOrderRepository : OrderRepository by FakeOrderRepository() {
        val hostingDrafts = mutableListOf<HostingOrderDraft>()
        val shortDrafts = mutableListOf<ShortOrderDraft>()

        override suspend fun createHostingOrder(draft: HostingOrderDraft): String {
            hostingDrafts += draft
            return "hosting-${hostingDrafts.size}"
        }

        override suspend fun createShortOrder(draft: ShortOrderDraft): String {
            shortDrafts += draft
            return "short-${shortDrafts.size}"
        }
    }

    private val hostingTemplate = TemplateFixtures.template(
        id = "tpl-hosting",
        gameName = "重返未来：1999",
        templateVersion = "2026.10",
        dailyRefresh = LocalTime.of(5, 0),
        weeklyTime = LocalTime.of(5, 0),
        categories = listOf(
            TemplateFixtures.category(
                templateId = "tpl-hosting",
                id = "c-daily",
                name = "日常",
                tasks = listOf(
                    TemplateFixtures.task(
                        templateId = "tpl-hosting",
                        categoryId = "c-daily",
                        id = "t-check",
                        title = "每日签到",
                    ),
                    TemplateFixtures.task(
                        templateId = "tpl-hosting",
                        categoryId = "c-daily",
                        id = "t-numeric",
                        title = "消耗活性",
                        progressType = ProgressType.NUMERIC,
                        startValue = "0",
                        targetValue = "120",
                        unit = "活性",
                    ),
                ),
            ),
            TemplateFixtures.category(
                templateId = "tpl-hosting",
                id = "c-once",
                name = "短期委托",
                tasks = listOf(
                    TemplateFixtures.task(
                        templateId = "tpl-hosting",
                        categoryId = "c-once",
                        id = "t-once",
                        title = "推进主线",
                        recurrence = RecurrenceType.ONE_TIME,
                    ),
                ),
            ),
        ),
    )

    private val dailyOnlyTemplate = TemplateFixtures.template(
        id = "tpl-daily-only",
        gameName = "只有日常",
        categories = listOf(
            TemplateFixtures.category(
                templateId = "tpl-daily-only",
                id = "c-daily",
                name = "日常",
                tasks = listOf(
                    TemplateFixtures.task(
                        templateId = "tpl-daily-only",
                        categoryId = "c-daily",
                        id = "t-only-daily",
                        title = "每日签到",
                    ),
                ),
            ),
        ),
    )

    private fun TestScope.viewModel(
        templates: FakeGameTemplateRepository,
        defaultTemplateId: String? = null,
    ): NewOrderViewModel {
        val owners = FakeOwnerRepository().apply { ownersById["owner-1"] = owner() }
        val accounts = FakeGameAccountRepository().apply {
            accountsById["account-1"] = account(defaultTemplateId = defaultTemplateId)
        }
        val viewModel = NewOrderViewModel(
            SavedStateHandle(
                mapOf(Routes.OWNER_ID_ARG to "owner-1", Routes.ACCOUNT_ID_ARG to "account-1"),
            ),
            owners,
            accounts,
            RecordingOrderRepository(),
            FixedTimeProvider(),
            templates,
        )
        runCurrent()
        return viewModel
    }

    @Test
    fun `an account default template fills hosting defaults and provenance`() = runTest {
        val templates = FakeGameTemplateRepository().apply { put(hostingTemplate) }
        val viewModel = viewModel(templates, defaultTemplateId = "tpl-hosting")

        val state = viewModel.uiState.value
        assertEquals(OrderType.HOSTING, state.type)
        assertEquals(listOf("每日签到", "消耗活性"), state.tasks.map { it.title })
        assertEquals("120", state.tasks[1].targetValue)
        assertEquals("活性", state.tasks[1].unit)
        assertEquals("Asia/Shanghai", state.zoneText)
        assertEquals(LocalTime.of(5, 0), state.dailyRefresh)
        assertEquals("tpl-hosting", state.selectedTemplateId)
        assertTrue(state.templateNotice.orEmpty().contains("账号默认模板"))
    }

    @Test
    fun `submitted provenance names the template version at creation`() = runTest {
        val templates = FakeGameTemplateRepository().apply { put(hostingTemplate) }
        val orders = RecordingOrderRepository()
        val owners = FakeOwnerRepository().apply { ownersById["owner-1"] = owner() }
        val accounts = FakeGameAccountRepository().apply {
            accountsById["account-1"] = account(defaultTemplateId = "tpl-hosting")
        }
        val viewModel = NewOrderViewModel(
            SavedStateHandle(
                mapOf(Routes.OWNER_ID_ARG to "owner-1", Routes.ACCOUNT_ID_ARG to "account-1"),
            ),
            owners,
            accounts,
            orders,
            FixedTimeProvider(),
            templates,
        )
        runCurrent()
        viewModel.updatePrice("300.00")
        viewModel.submit()
        runCurrent()

        val draft = orders.hostingDrafts.single()
        assertEquals("tpl-hosting", draft.sourceTemplate?.templateId)
        assertEquals("重返未来：1999", draft.sourceTemplate?.gameName)
        assertEquals("2026.10", draft.sourceTemplate?.templateVersion)
        assertEquals(listOf("每日签到", "消耗活性"), draft.tasks.map { it.title })
    }

    @Test
    fun `switching type re-derives defaults while tasks are untouched`() = runTest {
        val templates = FakeGameTemplateRepository().apply { put(hostingTemplate) }
        val viewModel = viewModel(templates, defaultTemplateId = "tpl-hosting")

        viewModel.selectType(OrderType.SHORT_TASK)
        runCurrent()

        assertEquals(listOf("推进主线"), viewModel.uiState.value.tasks.map { it.title })
        assertEquals(RecurrenceType.ONE_TIME, viewModel.uiState.value.tasks.single().recurrence)
        assertEquals("tpl-hosting", viewModel.uiState.value.selectedTemplateId)

        viewModel.selectType(OrderType.HOSTING)
        runCurrent()
        assertEquals(listOf("每日签到", "消耗活性"), viewModel.uiState.value.tasks.map { it.title })
    }

    @Test
    fun `edited tasks are never replaced by a type switch`() = runTest {
        val templates = FakeGameTemplateRepository().apply { put(hostingTemplate) }
        val viewModel = viewModel(templates, defaultTemplateId = "tpl-hosting")

        viewModel.updateTask(0) { it.copy(title = "我改过的任务") }
        viewModel.selectType(OrderType.SHORT_TASK)
        viewModel.selectType(OrderType.HOSTING)
        runCurrent()

        assertEquals("我改过的任务", viewModel.uiState.value.tasks[0].title)
        assertEquals(2, viewModel.uiState.value.tasks.size)
    }

    @Test
    fun `a template without short tasks leaves the current tasks untouched`() = runTest {
        val templates = FakeGameTemplateRepository().apply {
            put(hostingTemplate)
            put(dailyOnlyTemplate)
        }
        val viewModel = viewModel(templates, defaultTemplateId = "tpl-daily-only")
        assertEquals(listOf("每日签到"), viewModel.uiState.value.tasks.map { it.title })

        viewModel.selectType(OrderType.SHORT_TASK)
        runCurrent()

        assertEquals(listOf("每日签到"), viewModel.uiState.value.tasks.map { it.title })
        assertTrue(
            viewModel.uiState.value.templateNotice.orEmpty().contains("没有可用于短期单"),
        )
    }

    @Test
    fun `clearing the template removes provenance but keeps the tasks`() = runTest {
        val templates = FakeGameTemplateRepository().apply { put(hostingTemplate) }
        val orders = RecordingOrderRepository()
        val owners = FakeOwnerRepository().apply { ownersById["owner-1"] = owner() }
        val accounts = FakeGameAccountRepository().apply {
            accountsById["account-1"] = account(defaultTemplateId = "tpl-hosting")
        }
        val viewModel = NewOrderViewModel(
            SavedStateHandle(
                mapOf(Routes.OWNER_ID_ARG to "owner-1", Routes.ACCOUNT_ID_ARG to "account-1"),
            ),
            owners,
            accounts,
            orders,
            FixedTimeProvider(),
            templates,
        )
        runCurrent()
        viewModel.clearTemplate()
        viewModel.updatePrice("300.00")
        viewModel.submit()
        runCurrent()

        assertNull(viewModel.uiState.value.selectedTemplateId)
        assertEquals(2, viewModel.uiState.value.tasks.size)
        assertNull(orders.hostingDrafts.single().sourceTemplate)
    }

    @Test
    fun `applying a short template explicitly fills one-time tasks for a short order`() = runTest {
        val templates = FakeGameTemplateRepository().apply { put(hostingTemplate) }
        val viewModel = viewModel(templates)
        viewModel.selectType(OrderType.SHORT_TASK)
        runCurrent()
        viewModel.applyTemplate("tpl-hosting")
        runCurrent()

        assertEquals(OrderType.SHORT_TASK, viewModel.uiState.value.type)
        assertEquals("推进主线", viewModel.uiState.value.tasks.single().title)
        assertEquals("tpl-hosting", viewModel.uiState.value.selectedTemplateId)
    }
}
