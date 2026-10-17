package com.daigan.record.feature.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daigan.record.data.model.OwnerSummary
import com.daigan.record.data.repository.OwnerRepository
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
) : ViewModel() {

    val uiState: StateFlow<OwnersUiState> =
        combine<List<OwnerSummary>, List<OwnerSummary>, OwnersUiState>(
            ownerRepository.observeActiveOwners(),
            ownerRepository.observeArchivedOwners(),
        ) { active, archived ->
            OwnersUiState.Success(owners = active, archivedCount = archived.size)
        }
            .catch { emit(OwnersUiState.Error("读取老板资料失败，请重试。")) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = OwnersUiState.Loading,
            )
}
