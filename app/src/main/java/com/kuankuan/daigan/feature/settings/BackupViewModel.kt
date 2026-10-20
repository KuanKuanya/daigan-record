package com.kuankuan.daigan.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kuankuan.daigan.backup.BackupExportService
import com.kuankuan.daigan.backup.BackupExportSummary
import com.kuankuan.daigan.backup.BackupRestoreService
import com.kuankuan.daigan.backup.BackupStorage
import com.kuankuan.daigan.backup.RestorePreview
import com.kuankuan.daigan.backup.RestoreResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

sealed interface RestoreStage {
    data object Idle : RestoreStage

    data object Inspecting : RestoreStage

    /** The file is encrypted and a password is required before validation. */
    data object NeedsPassword : RestoreStage

    data object Preparing : RestoreStage

    /** Validated; waiting for explicit confirmation. */
    data class Ready(val preview: RestorePreview) : RestoreStage

    data object Applying : RestoreStage

    data class Success(val result: RestoreResult) : RestoreStage

    data class Failed(val message: String) : RestoreStage
}

data class BackupUiState(
    val exportRunning: Boolean = false,
    val exportPassword: String = "",
    val exportPasswordConfirm: String = "",
    val exportError: String? = null,
    val exportResult: BackupExportSummary? = null,
    val restoreStage: RestoreStage = RestoreStage.Idle,
    val restoreError: String? = null,
    val passwordInput: String = "",
    val message: String? = null,
) {
    val restoreBusy: Boolean
        get() = restoreStage is RestoreStage.Inspecting ||
            restoreStage is RestoreStage.Preparing ||
            restoreStage is RestoreStage.Applying

    val anyOperationRunning: Boolean get() = exportRunning || restoreBusy
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val exportService: BackupExportService,
    private val restoreService: BackupRestoreService,
    private val storage: BackupStorage,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    val maxPasswordLength = 256

    fun suggestedFileName(encrypted: Boolean): String =
        storage.backupFileName(storage.now(), encrypted)

    fun onExportPasswordChanged(value: String) {
        _uiState.value = _uiState.value.copy(
            exportPassword = value.take(maxPasswordLength),
            exportError = null,
        )
    }

    fun onExportPasswordConfirmChanged(value: String) {
        _uiState.value = _uiState.value.copy(
            exportPasswordConfirm = value.take(maxPasswordLength),
            exportError = null,
        )
    }

    /**
     * Exports to the document chosen by the system picker. A blank password produces an
     * ordinary (unencrypted) archive; a supplied password must be confirmed and is never
     * stored. Cancelling the picker is handled by the caller and changes nothing.
     */
    fun export(uri: Uri) {
        val state = _uiState.value
        if (state.anyOperationRunning) return
        val password = state.exportPassword
        val confirmation = state.exportPasswordConfirm
        if (password.isNotEmpty()) {
            if (password.length < MIN_PASSWORD_LENGTH) {
                _uiState.value = state.copy(
                    exportError = "密码至少 $MIN_PASSWORD_LENGTH 位，或留空导出不加密的备份。",
                )
                return
            }
            if (password != confirmation) {
                _uiState.value = state.copy(exportError = "两次输入的密码不一致。")
                return
            }
        }
        _uiState.value = state.copy(exportRunning = true, exportError = null, exportResult = null)
        viewModelScope.launch {
            val chars = if (password.isEmpty()) null else password.toCharArray()
            try {
                val result = exportService.export(uri, chars)
                _uiState.value = _uiState.value.copy(
                    exportRunning = false,
                    exportPassword = "",
                    exportPasswordConfirm = "",
                    exportResult = result,
                    message = if (result.encrypted) {
                        "备份已保存（已加密）。"
                    } else {
                        "备份已保存（未加密）。"
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    exportRunning = false,
                    exportError = error.message ?: "导出失败，未写入备份文件。",
                )
            } finally {
                chars?.fill('\u0000')
            }
        }
    }

    fun onExportCancelled() {
        _uiState.value = _uiState.value.copy(
            message = "已取消导出，没有写入任何文件。",
            exportError = null,
        )
    }

    /** Reads only the format header first; extraction waits for prepare(). */
    fun inspect(uri: Uri) {
        val state = _uiState.value
        if (state.anyOperationRunning) return
        if (state.restoreStage is RestoreStage.Success) return
        _uiState.value = state.copy(
            restoreStage = RestoreStage.Inspecting,
            restoreError = null,
        )
        viewModelScope.launch {
            runCatching { restoreService.inspect(uri) }
                .onSuccess { inspection ->
                    _uiState.value = _uiState.value.copy(
                        restoreStage = if (inspection.encrypted) {
                            RestoreStage.NeedsPassword
                        } else {
                            RestoreStage.Preparing
                        },
                        passwordInput = "",
                    )
                    if (!inspection.encrypted) {
                        prepare(null)
                    }
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        restoreStage = RestoreStage.Failed(
                            error.message ?: "无法读取备份文件。",
                        ),
                    )
                }
        }
    }

    fun onPasswordChanged(value: String) {
        _uiState.value = _uiState.value.copy(passwordInput = value.take(maxPasswordLength))
    }

    fun submitPassword() {
        if (_uiState.value.restoreStage !is RestoreStage.NeedsPassword) return
        val password = _uiState.value.passwordInput
        if (password.isEmpty()) {
            _uiState.value = _uiState.value.copy(restoreError = "请输入备份密码。")
            return
        }
        _uiState.value = _uiState.value.copy(
            restoreStage = RestoreStage.Preparing,
            restoreError = null,
        )
        prepare(password.toCharArray())
    }

    private fun prepare(password: CharArray?) {
        viewModelScope.launch {
            try {
                restoreService.prepare(password).let { preview ->
                    _uiState.value = _uiState.value.copy(
                        restoreStage = RestoreStage.Ready(preview), passwordInput = "", restoreError = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    restoreStage = RestoreStage.Failed(error.message ?: "备份校验失败，未替换资料。"),
                    passwordInput = "",
                )
            } finally {
                password?.fill('\u0000')
            }

        }
    }

    /** The destructive step runs only after the user explicitly confirms the summary. */
    fun confirmRestore() {
        if (_uiState.value.restoreStage !is RestoreStage.Ready) return
        _uiState.value = _uiState.value.copy(
            restoreStage = RestoreStage.Applying,
            restoreError = null,
        )
        viewModelScope.launch {
            runCatching { restoreService.applyConfirmed() }
                .onSuccess { result ->
                    _uiState.value = _uiState.value.copy(
                        restoreStage = RestoreStage.Success(result),
                        message = "恢复完成，当前资料已整体替换。",
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        restoreStage = RestoreStage.Failed(
                            error.message ?: "恢复未完成，请检查提示后重试。",
                        ),
                    )
                }
        }
    }

    fun cancelRestore() {
        if (_uiState.value.anyOperationRunning) return
        viewModelScope.launch { runCatching { restoreService.discardSession() } }
        _uiState.value = _uiState.value.copy(
            restoreStage = RestoreStage.Idle,
            restoreError = null,
            passwordInput = "",
            message = "已取消恢复，现有资料没有变化。",
        )
    }

    fun clearRestoreFailure() {
        _uiState.value = _uiState.value.copy(restoreStage = RestoreStage.Idle, restoreError = null)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private companion object {
        const val MIN_PASSWORD_LENGTH = 6
    }
}
