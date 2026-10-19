package com.daigan.record.feature.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daigan.record.data.model.OwnerSummary
import com.daigan.record.data.repository.OwnerRepository
import com.daigan.record.data.repository.OrderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class AccountsViewModel @Inject constructor(
    ownerRepository: OwnerRepository,
    orderRepository: OrderRepository,
) : ViewModel() {

    val uiState: StateFlow<OwnersUiState> =
        combine(
            ownerRepository.observeActiveOwners(),
            ownerRepository.observeArchivedOwners(),
            orderRepository.observeTodayHosting(),
            orderRepository.observeTodayShort(),
        ) { active, archived, hosting, shortOrders ->
            val unfinishedByOwner = (
                hosting.filter { !it.expired }.map { it.ownerId to it.order.id } +
                    shortOrders.filter { !it.order.completed }.map { it.ownerId to it.order.id }
                ).distinct().groupingBy { it.first }.eachCount()
            val owners = active.map { summary ->
                summary.copy(unfinishedOrderCount = unfinishedByOwner[summary.owner.id] ?: 0)
            }.sortedWith(
                compareByDescending<OwnerSummary> { it.unfinishedOrderCount > 0 }
                    .thenBy { it.owner.number },
            )
            OwnersUiState.Success(owners = owners, archivedCount = archived.size) as OwnersUiState
        }
            .catch { emit(OwnersUiState.Error("读取老板资料失败，请重试。")) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = OwnersUiState.Loading,
            )
}
