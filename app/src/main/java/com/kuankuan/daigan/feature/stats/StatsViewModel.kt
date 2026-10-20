package com.kuankuan.daigan.feature.stats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kuankuan.daigan.core.time.ReportingTimeZoneProvider
import com.kuankuan.daigan.core.time.StatisticsRange
import com.kuankuan.daigan.core.time.TimeProvider
import com.kuankuan.daigan.data.model.Statistics
import com.kuankuan.daigan.data.repository.StatisticsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatsUiState(
    val loading: Boolean = true,
    val range: StatisticsRange = StatisticsRange.MONTH,
    val statistics: Statistics? = null,
    val error: String? = null,
)

/**
 * Statistics always reload with the device's *current* reporting zone and clock, so a
 * month boundary, a timezone change or returning to the foreground re-derives the
 * calendar window instead of reusing a stale one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val statisticsRepository: StatisticsRepository,
    private val zoneProvider: ReportingTimeZoneProvider,
    private val timeProvider: TimeProvider,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val range = MutableStateFlow(StatisticsRange.MONTH)
    private val refreshTick = MutableStateFlow(0L)

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    private val timeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh()
        }
    }

    init {
        runCatching {
            ContextCompat.registerReceiver(
                context,
                timeChangeReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    addAction(Intent.ACTION_DATE_CHANGED)
                    addAction(Intent.ACTION_TIME_CHANGED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }

        viewModelScope.launch {
            combine(range, refreshTick) { selected, _ -> selected }
                .flatMapLatest { selected ->
                    _uiState.update {
                        it.copy(range = selected, loading = true, error = null)
                    }
                    statisticsRepository
                        .observeStatistics(selected, zoneProvider.zone(), timeProvider.now())
                        .map { statistics ->
                            StatsUiState(
                                loading = false,
                                range = selected,
                                statistics = statistics,
                            )
                        }
                        .catch { error ->
                            emit(
                                StatsUiState(
                                    loading = false,
                                    range = selected,
                                    error = error.message ?: "读取统计数据失败",
                                ),
                            )
                        }
                }
                .collect { state -> _uiState.value = state }
        }
    }

    fun selectRange(value: StatisticsRange) {
        range.value = value
    }

    /** Foreground return and system time/timezone broadcasts both land here. */
    fun refresh() {
        refreshTick.value += 1
    }

    override fun onCleared() {
        runCatching { context.unregisterReceiver(timeChangeReceiver) }
        super.onCleared()
    }
}
