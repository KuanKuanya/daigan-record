package com.daigan.record.feature.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daigan.record.data.model.AccountDraft
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.data.repository.ARCHIVED_OWNER_ACCOUNT_MESSAGE
import com.daigan.record.data.repository.GameAccountRepository
import com.daigan.record.data.repository.GameTemplateRepository
import com.daigan.record.data.repository.OwnerRepository
import com.daigan.record.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class AccountFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    ownerRepository: OwnerRepository,
    private val accountRepository: GameAccountRepository,
    private val templateRepository: GameTemplateRepository,
) : ViewModel() {

    private val ownerId: String = checkNotNull(savedStateHandle[Routes.OWNER_ID_ARG]) {
        "ownerId 参数缺失"
    }
    private val accountId: String? = savedStateHandle
        .get<String>(Routes.ACCOUNT_ID_ARG)
        ?.takeIf { it.isNotBlank() }

    private val _uiState = MutableStateFlow(
        AccountFormUiState(
            isEdit = accountId != null,
            isLoading = true,
        ),
    )
    val uiState: StateFlow<AccountFormUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // The library is a creation default only; an account never requires one.
            runCatching { templateRepository.ensureBuiltInTemplates() }
            val templates = runCatching { templateRepository.findAllSummaries() }
                .getOrDefault(emptyList())
            val owner = ownerRepository.findOwner(ownerId)
            val account = accountId?.let { accountRepository.findAccount(it) }
            _uiState.update { state ->
                val draft = account?.let(AccountDraft::from) ?: state.draft
                val selected = templates.firstOrNull { it.id == draft.defaultTemplateId }
                state.copy(
                    isLoading = false,
                    ownerNickname = owner?.nickname,
                    ownerArchived = owner?.archived == true,
                    draft = if (selected == null) draft.copy(defaultTemplateId = null) else draft,
                    templates = templates,
                    templateName = selected?.label,
                )
            }
        }
    }

    /**
     * Choosing a template stores it as this account's creation default and fills an empty
     * game name. Existing typed text is never overwritten; the user can clear the choice
     * at any time and existing orders are unaffected.
     */
    fun selectTemplate(templateId: String?) {
        _uiState.update { state ->
            val template = templateId?.let { id -> state.templates.firstOrNull { it.id == id } }
            state.copy(
                draft = state.draft.copy(
                    defaultTemplateId = template?.id,
                    gameName = if (template != null && state.draft.gameName.isBlank()) {
                        template.gameName
                    } else {
                        state.draft.gameName
                    },
                ),
                templateName = template?.label,
                errors = emptyMap(),
                saveError = null,
            )
        }
    }

    fun onDraftChange(transform: (AccountDraft) -> AccountDraft) {
        _uiState.update { state ->
            state.copy(
                draft = transform(state.draft),
                errors = emptyMap(),
                saveError = null,
            )
        }
    }

    /**
     * Switching to verification-code login also drops the in-memory password so the
     * submitted draft can never carry a stale credential.
     */
    fun selectLoginMethod(method: LoginMethod) {
        _uiState.update { state ->
            val draft = state.draft.copy(loginMethod = method)
            state.copy(
                draft = if (method == LoginMethod.VERIFICATION_CODE) draft.copy(password = "") else draft,
                errors = emptyMap(),
                saveError = null,
            )
        }
    }

    fun save() {
        val state = _uiState.value
        if (state.isSaving || state.isLoading) return
        // The owner may have been archived while this form was open. The repository
        // rejects the write inside its transaction as well; this is the fast path so a
        // stale form cannot even try.
        if (state.ownerArchived) {
            _uiState.update { it.copy(saveError = ARCHIVED_OWNER_ACCOUNT_MESSAGE) }
            return
        }
        val errors = state.draft.validate()
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(errors = errors) }
            return
        }
        _uiState.update { it.copy(isSaving = true, saveError = null) }
        viewModelScope.launch {
            val result = runCatching {
                if (accountId == null) {
                    accountRepository.createAccount(ownerId, state.draft)
                } else {
                    accountRepository.updateAccount(accountId, state.draft)
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
