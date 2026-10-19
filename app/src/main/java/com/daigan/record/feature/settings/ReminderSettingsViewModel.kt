package com.daigan.record.feature.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daigan.record.notifications.ReminderCategory
import com.daigan.record.notifications.ReminderCoordinator
import com.daigan.record.notifications.ReminderSettings
import com.daigan.record.notifications.ReminderSettingsRepository
import com.daigan.record.notifications.ReminderTiming
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReminderSettingsUiState(
    val loading: Boolean = true,
    val settings: ReminderSettings = ReminderSettings(),
    val notificationsAllowed: Boolean = true,
    val saving: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class ReminderSettingsViewModel @Inject constructor(
    private val repository: ReminderSettingsRepository,
    private val coordinator: ReminderCoordinator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ReminderSettingsUiState(notificationsAllowed = coordinator.canNotify()),
    )
    val uiState: StateFlow<ReminderSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observe().collect { settings ->
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    settings = settings,
                    notificationsAllowed = coordinator.canNotify(),
                )
            }
        }
    }

    fun setEnabled(category: ReminderCategory, enabled: Boolean) {
        persist(_uiState.value.settings.withEnabled(category, enabled))
    }

    fun setTime(category: ReminderCategory, minutes: Int) {
        persist(_uiState.value.settings.withMinutes(category, minutes))
    }

    /** Called after the system permission dialog returns or when the screen resumes. */
    fun refreshPermission() {
        _uiState.value = _uiState.value.copy(
            notificationsAllowed = coordinator.canNotify(),
        )
    }

    fun notificationSettingsIntent(): Intent = coordinator.notificationSettingsIntent()

    fun formatTime(category: ReminderCategory): String =
        ReminderTiming.formatTime(_uiState.value.settings.minutes(category))

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private fun persist(settings: ReminderSettings) {
        _uiState.value = _uiState.value.copy(settings = settings, saving = true)
        viewModelScope.launch {
            runCatching { coordinator.saveSettings(settings) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        saving = false,
                        notificationsAllowed = coordinator.canNotify(),
                        message = if (settings.anyEnabled && !coordinator.canNotify()) {
                            "提醒已保存，但系统通知权限未开启，暂时不会收到通知。"
                        } else {
                            "提醒设置已保存。"
                        },
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        saving = false,
                        message = error.message ?: "保存提醒设置失败，请重试。",
                    )
                }
        }
    }
}
