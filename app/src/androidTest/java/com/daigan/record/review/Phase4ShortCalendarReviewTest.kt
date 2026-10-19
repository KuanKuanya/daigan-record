package com.daigan.record.review

import com.daigan.record.core.time.GameRefreshRules
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.*
import com.daigan.record.feature.orders.NewOrderViewModel
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.ui.navigation.Routes
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** The displayed template calendar must survive short-order creation as a snapshot. */
@RunWith(AndroidJUnit4::class)
class Phase4ShortCalendarReviewTest {
    @Test fun shortOrderUsesTheSelectedTemplateGameDayInsteadOfProvisionalRefresh() = runBlocking {
        val env = OrderTestEnvironment(ApplicationProvider.getApplicationContext<Context>())
        try {
            // 04:30 in Shanghai is still Oct 17 for a template refreshing at 05:00.
            env.clock.set(Instant.parse("2026-10-17T20:30:00Z"))
            val (owner, account) = env.seedOwnerAndAccount()
            val templateId = env.templateRepository.saveTemplate(TemplateDraft(
                gameName = "五点刷新测试", dailyRefresh = LocalTime.of(5, 0),
                categories = listOf(TemplateCategoryDraft(name = "短期", tasks = listOf(
                    TemplateTaskDraft(title = "主线交付", recurrence = RecurrenceType.ONE_TIME),
                ))),
            ))
            val vm = withContext(Dispatchers.Main) {
                NewOrderViewModel(
                    SavedStateHandle(mapOf(Routes.OWNER_ID_ARG to owner.id, Routes.ACCOUNT_ID_ARG to account.id)),
                    env.ownerRepository, env.accountRepository, env.orderRepository, env.clock,
                    env.templateRepository,
                )
            }
            withTimeout(10_000) { while (vm.uiState.value.loading) delay(10) }
            withContext(Dispatchers.Main) {
                vm.selectType(OrderType.SHORT_TASK)
                vm.applyTemplate(templateId)
            }
            withTimeout(10_000) {
                while (vm.uiState.value.selectedTemplateId != templateId) delay(10)
            }
            assertEquals(LocalTime.of(5, 0), vm.uiState.value.dailyRefresh)
            withContext(Dispatchers.Main) {
                vm.updatePrice("100.00")
                vm.setDeadline(LocalDate.of(2026, 10, 17))
                vm.submit()
            }
            withTimeout(10_000) { while (vm.uiState.value.createdOrderId == null) delay(10) }
            val id = checkNotNull(vm.uiState.value.createdOrderId)
            val detail = env.orderRepository.observeOrderDetail(id).first()!!
            assertEquals("Short-order snapshot must retain the selected template refresh",
                LocalTime.of(5, 0), detail.order.refresh.dailyRefresh)
            assertEquals(LocalDate.of(2026, 10, 17), detail.today)
            assertFalse(env.orderRepository.observeTodayShort().first().single { it.order.id == id }.overdue)
            env.templateRepository.deleteTemplate(templateId)
            assertEquals(LocalTime.of(5, 0),
                env.orderRepository.observeOrderDetail(id).first()!!.order.refresh.dailyRefresh)
        } finally {
            env.close()
        }
    }
    @Test fun calendarSnapshotSurvivesReopenAndRejectsChangedCalendarRetry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "phase4-calendar-${UUID.randomUUID()}.db"
        var env = OrderTestEnvironment(context, databaseName = name, inMemory = false)
        try {
            val (owner, account) = env.seedOwnerAndAccount()
            val rules = GameRefreshRules.provisional(ZoneId.of("Asia/Shanghai"))
                .copy(dailyRefresh = LocalTime.of(5, 0))
            val draft = ShortOrderDraft(owner.id, account.id, priceYuan = "100",
                deadlineDate = LocalDate.of(2026, 10, 17), refresh = rules,
                tasks = listOf(TaskDraft(title = "交付", recurrence = RecurrenceType.ONE_TIME)))
            env.clock.set(Instant.parse("2026-10-17T20:30:00Z"))
            val id = env.orderRepository.createShortOrder(draft)
            assertEquals(id, env.orderRepository.createShortOrder(draft))
            val conflict = runCatching { env.orderRepository.createShortOrder(
                draft.copy(refresh = rules.copy(zoneId = ZoneId.of("UTC")))) }.exceptionOrNull()
            assertTrue(conflict is IllegalArgumentException)
            env.close(deleteDatabaseFile = false)
            env = OrderTestEnvironment(context, databaseName = name, inMemory = false,
                initialInstant = Instant.parse("2026-10-17T20:30:00Z"))
            assertEquals(id, env.orderRepository.createShortOrder(draft))
            val snapshot = env.orderRepository.observeOrderDetail(id).first()!!.order.refresh
            assertEquals(rules.zoneId, snapshot.zoneId)
            assertEquals(rules.dailyRefresh, snapshot.dailyRefresh)
            assertEquals(rules.weeklyRefreshDay, snapshot.weeklyRefreshDay)
            assertEquals(rules.weeklyRefreshTime, snapshot.weeklyRefreshTime)
            assertFalse(env.orderRepository.observeTodayShort().first().single { it.order.id == id }.overdue)
            env.clock.set(Instant.parse("2026-10-17T21:00:00Z"))
            assertEquals(LocalDate.of(2026, 10, 18), env.orderRepository.observeOrderDetail(id).first()!!.today)
            assertTrue(env.orderRepository.observeTodayShort().first().single { it.order.id == id }.overdue)
            // Existing null-calendar commands retain their original idempotency contract.
            val legacy = draft.copy(refresh = null, creationOperationId = "legacy-${UUID.randomUUID()}")
            val legacyId = env.orderRepository.createShortOrder(legacy)
            assertEquals(legacyId, env.orderRepository.createShortOrder(legacy))
            assertNotEquals(id, legacyId)
        } finally { env.close() }
    }

}
