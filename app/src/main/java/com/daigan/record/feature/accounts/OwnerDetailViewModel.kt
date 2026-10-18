package com.daigan.record.feature.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daigan.record.data.repository.GameAccountRepository
import com.daigan.record.data.repository.OrderRepository
import com.daigan.record.data.repository.OwnerRepository
import com.daigan.record.ui.navigation.Routes
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
class OwnerDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val ownerRepository: OwnerRepository,
    private val accountRepository: GameAccountRepository,
    orderRepository: OrderRepository,
) : ViewModel() {

    private val ownerId: String = checkNotNull(savedStateHandle[Routes.OWNER_ID_ARG]) {
        "ownerId 参数缺失"
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    val uiState: StateFlow<OwnerDetailUiState> = combine(
        ownerRepository.observeOwner(ownerId),
        orderRepository.observeOrdersForOwner(ownerId),
    ) { detail, orders ->
        if (detail == null) {
            OwnerDetailUiState.NotFound
        } else {
            OwnerDetailUiState.Success(
                detail = detail,
                ordersByAccount = orders.groupBy { it.accountId },
            )
        }
    }
        .catch { emit(OwnerDetailUiState.Error("读取老板资料失败，请重试。")) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = OwnerDetailUiState.Loading,
        )

    fun archiveOwner() {
        viewModelScope.launch {
            runCatching { ownerRepository.archiveOwner(ownerId) }
                .onSuccess { _messages.emit("已归档老板，可在归档记录中恢复") }
                .onFailure { _messages.emit("归档失败：${it.message ?: "未知错误"}") }
        }
    }

    fun restoreOwner() {
        viewModelScope.launch {
            runCatching { ownerRepository.restoreOwner(ownerId) }
                .onSuccess { _messages.emit("已恢复老板") }
                .onFailure { _messages.emit("恢复失败：${it.message ?: "未知错误"}") }
        }
    }

    fun archiveAccount(accountId: String) {
        viewModelScope.launch {
            runCatching { accountRepository.archiveAccount(accountId) }
                .onSuccess { _messages.emit("已归档游戏账号") }
                .onFailure { _messages.emit("归档失败：${it.message ?: "未知错误"}") }
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
