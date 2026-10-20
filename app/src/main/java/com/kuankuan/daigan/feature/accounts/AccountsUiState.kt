package com.kuankuan.daigan.feature.accounts

import com.kuankuan.daigan.data.model.AccountDraft
import com.kuankuan.daigan.data.model.AccountField
import com.kuankuan.daigan.data.model.ArchivedAccount
import com.kuankuan.daigan.data.model.OwnerDetail
import com.kuankuan.daigan.data.model.OwnerSummary

sealed interface OwnersUiState {
    data object Loading : OwnersUiState

    data class Success(
        val owners: List<OwnerSummary>,
        val archivedCount: Int,
    ) : OwnersUiState

    data class Error(val message: String) : OwnersUiState
}

sealed interface OwnerDetailUiState {
    data object Loading : OwnerDetailUiState

    data class Success(
        val detail: OwnerDetail,
        val ordersByAccount: Map<String, List<com.kuankuan.daigan.data.model.OrderSummary>> =
            emptyMap(),
    ) : OwnerDetailUiState

    data object NotFound : OwnerDetailUiState

    data class Error(val message: String) : OwnerDetailUiState
}

data class OwnerFormUiState(
    val isEdit: Boolean = false,
    val numberLabel: String = "自动生成",
    val nickname: String = "",
    val nicknameError: String? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val completed: Boolean = false,
)

data class AccountFormUiState(
    val isEdit: Boolean = false,
    val ownerNickname: String? = null,
    /** True when the form was opened before its owner was archived (stale form). */
    val ownerArchived: Boolean = false,
    val draft: AccountDraft = AccountDraft(),
    val errors: Map<AccountField, String> = emptyMap(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val completed: Boolean = false,
    /** Optional template preselected for new orders on this account. */
    val templates: List<com.kuankuan.daigan.data.model.GameTemplateSummary> = emptyList(),
    val templateName: String? = null,
)

data class ArchivedRecordsUiState(
    val isLoading: Boolean = true,
    val archivedOwners: List<OwnerSummary> = emptyList(),
    val archivedAccounts: List<ArchivedAccount> = emptyList(),
    val error: String? = null,
)
