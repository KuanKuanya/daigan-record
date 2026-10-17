package com.daigan.record.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daigan.record.core.time.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class TodayTab(val label: String) {
    HOSTING("托管打卡"),
    SHORT_TASK("短期任务"),
}

data class TodayUiState(
    val dateLabel: String = "",
    val selectedTab: TodayTab = TodayTab.HOSTING,
)

@HiltViewModel
class TodayViewModel @Inject constructor(
    timeProvider: TimeProvider,
) : ViewModel() {

    private val selectedTab = MutableStateFlow(TodayTab.HOSTING)

    private val dateLabel = DateTimeFormatter
        .ofPattern("M 月 d 日 · EEEE", Locale.SIMPLIFIED_CHINESE)
        .format(timeProvider.now().atZone(ZoneId.systemDefault()))

    val uiState: StateFlow<TodayUiState> = selectedTab
        .map { tab -> TodayUiState(dateLabel = dateLabel, selectedTab = tab) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TodayUiState(dateLabel = dateLabel, selectedTab = TodayTab.HOSTING),
        )

    fun selectTab(tab: TodayTab) {
        selectedTab.value = tab
    }
}
