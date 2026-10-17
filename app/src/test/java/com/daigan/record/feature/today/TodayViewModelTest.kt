package com.daigan.record.feature.today

import com.daigan.record.testing.FixedTimeProvider
import com.daigan.record.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TodayViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `defaults to the hosting tab with the injected date`() = runTest {
        val viewModel = TodayViewModel(FixedTimeProvider())

        val state = viewModel.uiState.first()

        assertEquals(TodayTab.HOSTING, state.selectedTab)
        assertTrue(state.dateLabel.contains("10 月 17 日"))
    }

    @Test
    fun `tab selection is reflected in the UI state`() = runTest {
        val viewModel = TodayViewModel(FixedTimeProvider())

        viewModel.selectTab(TodayTab.SHORT_TASK)

        val state = viewModel.uiState.first { it.selectedTab == TodayTab.SHORT_TASK }
        assertEquals(TodayTab.SHORT_TASK, state.selectedTab)
    }
}
