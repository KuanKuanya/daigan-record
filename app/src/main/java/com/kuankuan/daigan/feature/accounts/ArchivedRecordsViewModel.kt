package com.kuankuan.daigan.feature.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kuankuan.daigan.data.repository.GameAccountRepository
import com.kuankuan.daigan.data.repository.OwnerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ArchivedRecordsViewModel @Inject constructor(
    private val ownerRepository: OwnerRepository,
    private val accountRepository: GameAccountRepository,
) : ViewModel() {

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    val uiState: StateFlow<ArchivedRecordsUiState> =
        combine(
            ownerRepository.observeArchivedOwners(),
            accountRepository.observeArchivedAccounts(),
        ) { owners, accounts ->
            ArchivedRecordsUiState(
                isLoading = false,
                archivedOwners = owners,
                archivedAccounts = accounts,
            )
        }
            .catch {
                emit(ArchivedRecordsUiState(isLoading = false, error = "读取归档记录失败，请重试。"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ArchivedRecordsUiState(),
            )

    fun restoreOwner(ownerId: String) {
        viewModelScope.launch {
            runCatching { ownerRepository.restoreOwner(ownerId) }
                // Accounts archived separately stay archived, so do not promise more.
                .onSuccess { _messages.emit("已恢复老板") }
                .onFailure { _messages.emit("恢复失败：${it.message ?: "未知错误"}") }
        }
    }

    fun restoreAccount(accountId: String) {
        viewModelScope.launch {
            runCatching { accountRepository.restoreAccount(accountId) }
                .onSuccess { _messages.emit("已恢复游戏账号") }
                .onFailure { _messages.emit("恢复失败：${it.message ?: "未知错误"}") }
        }
    }
}
