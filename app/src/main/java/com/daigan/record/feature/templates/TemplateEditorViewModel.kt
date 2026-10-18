package com.daigan.record.feature.templates

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daigan.record.data.model.TemplateCategoryDraft
import com.daigan.record.data.model.TemplateDraft
import com.daigan.record.data.model.TemplateOrigin
import com.daigan.record.data.model.TemplateTaskDraft
import com.daigan.record.data.repository.GameTemplateRepository
import com.daigan.record.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TemplateEditorUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val loadError: String? = null,
    val draft: TemplateDraft = TemplateDraft(),
    val origin: TemplateOrigin? = null,
    val errors: Map<String, String> = emptyMap(),
    val saveError: String? = null,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val completed: Boolean = false,
) {
    val errorMessages: List<String> get() = errors.values.distinct()
}

@HiltViewModel
class TemplateEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val templateRepository: GameTemplateRepository,
) : ViewModel() {

    private val templateId: String? = savedStateHandle
        .get<String>(Routes.TEMPLATE_ID_ARG)
        ?.takeIf { it.isNotBlank() }

    private val _uiState = MutableStateFlow(
        TemplateEditorUiState(
            isNew = templateId == null,
            origin = if (templateId == null) TemplateOrigin.USER else null,
        ),
    )
    val uiState: StateFlow<TemplateEditorUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            if (templateId == null) {
                _uiState.update { it.copy(loading = false) }
                return@launch
            }
            val template = runCatching { templateRepository.findTemplate(templateId) }.getOrNull()
            if (template == null) {
                _uiState.update {
                    it.copy(loading = false, loadError = "找不到这个模板，它可能已被删除")
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    loading = false,
                    draft = TemplateDraft.from(template),
                    origin = template.origin,
                    dirty = false,
                )
            }
        }
    }

    fun updateDraft(transform: (TemplateDraft) -> TemplateDraft) {
        _uiState.update {
            it.copy(
                draft = transform(it.draft),
                dirty = true,
                errors = emptyMap(),
                saveError = null,
            )
        }
    }

    fun addCategory() = updateDraft { draft ->
        if (draft.categories.size >= TemplateDraft.MAX_CATEGORIES) draft
        else draft.copy(categories = draft.categories + TemplateCategoryDraft())
    }

    fun removeCategory(index: Int) = updateDraft { draft ->
        if (draft.categories.size <= 1) draft
        else draft.copy(categories = draft.categories.filterIndexed { i, _ -> i != index })
    }

    fun updateCategory(index: Int, transform: (TemplateCategoryDraft) -> TemplateCategoryDraft) =
        updateDraft { draft ->
            draft.copy(
                categories = draft.categories.mapIndexed { i, category ->
                    if (i == index) transform(category) else category
                },
            )
        }

    fun addTask(categoryIndex: Int) = updateCategory(categoryIndex) { category ->
        category.copy(tasks = category.tasks + TemplateTaskDraft())
    }

    fun removeTask(categoryIndex: Int, taskIndex: Int) = updateCategory(categoryIndex) { category ->
        if (category.tasks.size <= 1) category
        else category.copy(tasks = category.tasks.filterIndexed { i, _ -> i != taskIndex })
    }

    fun updateTask(
        categoryIndex: Int,
        taskIndex: Int,
        transform: (TemplateTaskDraft) -> TemplateTaskDraft,
    ) = updateCategory(categoryIndex) { category ->
        category.copy(
            tasks = category.tasks.mapIndexed { i, task ->
                if (i == taskIndex) transform(task) else task
            },
        )
    }

    fun save() {
        val state = _uiState.value
        if (state.saving || state.loading) return
        val errors = state.draft.validate()
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(errors = errors, saveError = null) }
            return
        }
        _uiState.update { it.copy(saving = true, saveError = null, errors = emptyMap()) }
        viewModelScope.launch {
            runCatching { templateRepository.saveTemplate(state.draft, templateId) }
                .onSuccess {
                    _uiState.update { it.copy(saving = false, completed = true, dirty = false) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            saving = false,
                            saveError = error.message ?: "保存失败，请重试",
                        )
                    }
                }
        }
    }
}
