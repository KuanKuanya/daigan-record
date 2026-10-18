package com.daigan.record.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daigan.record.data.model.ReceiptHistoryEntry
import com.daigan.record.data.repository.ReceiptRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class ReceiptHistoryUiState(
    val loading: Boolean = true,
    val entries: List<ReceiptHistoryEntry> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class ReceiptHistoryViewModel @Inject constructor(
    receiptRepository: ReceiptRepository,
) : ViewModel() {

    val uiState: StateFlow<ReceiptHistoryUiState> = receiptRepository
        .observeReceiptHistory()
        .map { entries -> ReceiptHistoryUiState(loading = false, entries = entries) }
        .catch { error ->
            emit(
                ReceiptHistoryUiState(
                    loading = false,
                    error = error.message ?: "读取收款明细失败",
                ),
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ReceiptHistoryUiState(),
        )
}
