package com.kuankuan.daigan.review

import androidx.lifecycle.SavedStateHandle
import com.kuankuan.daigan.data.model.ShortOrderDraft
import com.kuankuan.daigan.data.repository.OrderRepository
import org.junit.Assert.assertTrue
import com.kuankuan.daigan.data.model.OrderType
import com.kuankuan.daigan.data.model.GameTemplate
import com.kuankuan.daigan.data.repository.GameTemplateRepository
import com.kuankuan.daigan.feature.orders.NewOrderViewModel
import com.kuankuan.daigan.testing.*
import com.kuankuan.daigan.ui.navigation.Routes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** An older asynchronous template load must not override a later user decision. */
@OptIn(ExperimentalCoroutinesApi::class)
class Phase4TemplateSelectionReviewTest {
    @get:Rule val main = MainDispatcherRule()

    private fun vm(repository: GameTemplateRepository, orders: OrderRepository = FakeOrderRepository()): NewOrderViewModel {
        val owners = FakeOwnerRepository().apply { ownersById["owner-1"] = owner() }
        val accounts = FakeGameAccountRepository().apply { accountsById["account-1"] = account() }
        return NewOrderViewModel(
            SavedStateHandle(mapOf(Routes.OWNER_ID_ARG to "owner-1", Routes.ACCOUNT_ID_ARG to "account-1")),
            owners, accounts, orders, FixedTimeProvider(), repository,
        )
    }

    @Test fun laterTemplateSelectionWinsEvenWhenAnOlderReadFinishesLast() = runTest {
        val a = TemplateFixtures.template(id = "A", gameName = "模板甲")
        val b = TemplateFixtures.template(id = "B", gameName = "模板乙")
        val fake = FakeGameTemplateRepository().apply { put(a); put(b) }
        val gate = CompletableDeferred<GameTemplate?>()
        val repository = object : GameTemplateRepository by fake {
            override suspend fun findTemplate(templateId: String): GameTemplate? =
                if (templateId == "A") gate.await() else fake.findTemplate(templateId)
        }
        val viewModel = vm(repository)
        runCurrent()
        viewModel.applyTemplate("A")
        runCurrent()
        viewModel.applyTemplate("B")
        runCurrent()
        assertEquals("B", viewModel.uiState.value.selectedTemplateId)
        gate.complete(a)
        runCurrent()
        assertEquals("An obsolete template read must not overwrite the latest selection", "B",
            viewModel.uiState.value.selectedTemplateId)
    }

    @Test fun clearingTemplateCancelsTheEffectOfAnAlreadyPendingRead() = runTest {
        val template = TemplateFixtures.template(id = "A")
        val fake = FakeGameTemplateRepository().apply { put(template) }
        val gate = CompletableDeferred<GameTemplate?>()
        val repository = object : GameTemplateRepository by fake {
            override suspend fun findTemplate(templateId: String): GameTemplate? = gate.await()
        }
        val viewModel = vm(repository)
        runCurrent()
        viewModel.applyTemplate("A")
        runCurrent()
        viewModel.clearTemplate()
        viewModel.updateTask(0) { it.copy(title = "用户保留的任务") }
        gate.complete(template)
        runCurrent()
        assertNull("A cleared selection must not reappear after its read completes",
            viewModel.uiState.value.selectedTemplateId)
        assertEquals("用户保留的任务", viewModel.uiState.value.tasks.single().title)
    }
    @Test fun editsDuringTypeSwitchReadArePreserved() = runTest {
        val template = TemplateFixtures.template(id = "A")
        val fake = FakeGameTemplateRepository().apply { put(template) }
        var block = false
        val gate = CompletableDeferred<GameTemplate?>()
        val repository = object : GameTemplateRepository by fake {
            override suspend fun findTemplate(templateId: String): GameTemplate? =
                if (block) gate.await() else fake.findTemplate(templateId)
        }
        val viewModel = vm(repository)
        runCurrent()
        viewModel.applyTemplate("A")
        runCurrent()
        block = true
        viewModel.selectType(OrderType.SHORT_TASK)
        runCurrent()
        viewModel.updateTask(0) { it.copy(title = "用户新任务") }
        gate.complete(template)
        runCurrent()
        assertEquals("用户新任务", viewModel.uiState.value.tasks.first().title)
    }

    @Test fun submissionInvalidatesPendingTemplateRead() = runTest {
        val template = TemplateFixtures.template(id = "A")
        val fake = FakeGameTemplateRepository().apply { put(template) }
        val gate = CompletableDeferred<GameTemplate?>()
        val repository = object : GameTemplateRepository by fake {
            override suspend fun findTemplate(templateId: String): GameTemplate? = gate.await()
        }
        val submission = CompletableDeferred<Unit>()
        val drafts = mutableListOf<ShortOrderDraft>()
        val orders = object : OrderRepository by FakeOrderRepository() {
            override suspend fun createShortOrder(draft: ShortOrderDraft): String {
                drafts += draft
                submission.await()
                return "created-order"
            }
        }
        val viewModel = vm(repository, orders)
        runCurrent()
        viewModel.selectType(OrderType.SHORT_TASK)
        viewModel.updatePrice("100")
        viewModel.updateTask(0) { it.copy(title = "提交任务") }
        viewModel.applyTemplate("A")
        runCurrent()
        viewModel.submit()
        runCurrent()
        assertTrue(viewModel.uiState.value.saving)
        gate.complete(template)
        runCurrent()
        assertNull(viewModel.uiState.value.selectedTemplateId)
        assertEquals("提交任务", viewModel.uiState.value.tasks.single().title)
        assertEquals("提交任务", drafts.single().tasks.single().title)
        submission.complete(Unit)
        runCurrent()
        assertEquals("created-order", viewModel.uiState.value.createdOrderId)
    }

}
