package com.kuankuan.daigan.feature.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kuankuan.daigan.data.model.OwnerNumber
import com.kuankuan.daigan.data.model.OwnerValidation
import com.kuankuan.daigan.data.repository.OwnerRepository
import com.kuankuan.daigan.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class OwnerFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val ownerRepository: OwnerRepository,
) : ViewModel() {

    private val ownerId: String? = savedStateHandle
        .get<String>(Routes.OWNER_ID_ARG)
        ?.takeIf { it.isNotBlank() }

    private val _uiState = MutableStateFlow(
        OwnerFormUiState(
            isEdit = ownerId != null,
            isLoading = ownerId != null,
        ),
    )
    val uiState: StateFlow<OwnerFormUiState> = _uiState.asStateFlow()

    init {
        if (ownerId != null) {
            viewModelScope.launch {
                val owner = ownerRepository.findOwner(ownerId)
                _uiState.update { state ->
                    if (owner == null) {
                        state.copy(isLoading = false, saveError = "老板资料不存在")
                    } else {
                        state.copy(
                            isLoading = false,
                            nickname = owner.nickname,
                            numberLabel = OwnerNumber.format(owner.number),
                        )
                    }
                }
            }
        }
    }

    fun onNicknameChange(value: String) {
        _uiState.update { it.copy(nickname = value, nicknameError = null, saveError = null) }
    }

    fun save() {
        val state = _uiState.value
        if (state.isSaving || state.isLoading) return
        val nicknameError = OwnerValidation.nicknameError(state.nickname)
        if (nicknameError != null) {
            _uiState.update { it.copy(nicknameError = nicknameError) }
            return
        }
        _uiState.update { it.copy(isSaving = true, saveError = null) }
        viewModelScope.launch {
            val result = runCatching {
                if (ownerId == null) {
                    ownerRepository.createOwner(state.nickname)
                } else {
                    ownerRepository.renameOwner(ownerId, state.nickname)
                }
            }
            _uiState.update { current ->
                result.fold(
                    onSuccess = { current.copy(isSaving = false, completed = true) },
                    onFailure = {
                        current.copy(
                            isSaving = false,
                            saveError = it.message ?: "保存失败，请重试。",
                        )
                    },
                )
            }
        }
    }
}
