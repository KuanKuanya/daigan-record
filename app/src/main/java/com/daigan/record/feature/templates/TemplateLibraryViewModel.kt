package com.daigan.record.feature.templates

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daigan.record.data.model.GameTemplateSummary
import com.daigan.record.data.model.TemplateDraft
import com.daigan.record.data.repository.GameTemplateRepository
import com.daigan.record.data.repository.TemplateImportResolution
import com.daigan.record.data.template.TemplateFileCodec
import com.daigan.record.data.template.TemplateFileContent
import com.daigan.record.data.template.TemplateFileRepository
import com.daigan.record.data.template.TemplateFileResult
import com.daigan.record.data.template.TemplateNames
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Validated import waiting for an explicit replace / save-as / cancel decision. */
data class TemplateImportPreview(
    val fileName: String?,
    val templateId: String,
    val draft: TemplateDraft,
    val categoryCount: Int,
    val taskCount: Int,
    val conflict: GameTemplateSummary?,
    /** The name save-as will actually use, computed against the whole library. */
    val saveAsName: String,
) {
    val conflictName: String? get() = conflict?.label
}

data class TemplateLibraryUiState(
    val loading: Boolean = true,
    val templates: List<GameTemplateSummary> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val importPreview: TemplateImportPreview? = null,
    val deleteCandidate: GameTemplateSummary? = null,
)

@HiltViewModel
class TemplateLibraryViewModel @Inject constructor(
    private val templateRepository: GameTemplateRepository,
    private val fileRepository: TemplateFileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TemplateLibraryUiState())
    val uiState: StateFlow<TemplateLibraryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Installing missing built-ins is idempotent and never overwrites an edit.
            runCatching { templateRepository.ensureBuiltInTemplates() }
            templateRepository.observeLibrary().collect { templates ->
                _uiState.update { it.copy(loading = false, templates = templates) }
            }
        }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    // ------------------------------------------------------------------ import

    /**
     * Reads and fully validates the selected document. Nothing is written until the user
     * confirms in the preview, and a failure leaves the library exactly as it was.
     */
    fun onImportPicked(uri: Uri, fileName: String? = null) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = runCatching { fileRepository.read(uri) }
            val bytes = result.getOrNull()
            if (bytes == null) {
                _uiState.update {
                    it.copy(
                        busy = false,
                        message = result.exceptionOrNull()?.message ?: "无法读取所选文件，请重试",
                    )
                }
                return@launch
            }
            when (val decoded = TemplateFileCodec.decode(bytes)) {
                is TemplateFileResult.Failure -> _uiState.update {
                    it.copy(busy = false, message = decoded.message)
                }

                is TemplateFileResult.Success -> {
                    val content = decoded.content
                    val conflict = runCatching {
                        templateRepository.findNameConflict(content.draft.gameName)
                    }.getOrNull()
                    val saveAsName = TemplateNames.uniqueGameName(
                        base = content.draft.gameName,
                        existingNames = _uiState.value.templates.map { it.gameName },
                    )
                    _uiState.update {
                        it.copy(
                            busy = false,
                            importPreview = content.toPreview(fileName, conflict, saveAsName),
                        )
                    }
                }
            }
        }
    }

    fun cancelImport() = _uiState.update {
        it.copy(importPreview = null, message = "已取消导入，模板库没有变化")
    }

    fun confirmImportSaveAs() {
        applyImport(TemplateImportResolution.SaveAs)
    }

    fun confirmImportReplace() {
        val preview = _uiState.value.importPreview ?: return
        val target = preview.conflict ?: return
        applyImport(TemplateImportResolution.Replace(target.id))
    }

    private fun applyImport(resolution: TemplateImportResolution) {
        val preview = _uiState.value.importPreview ?: return
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val content = TemplateFileContent(
                templateId = preview.templateId,
                draft = preview.draft,
                categoryCount = preview.categoryCount,
                taskCount = preview.taskCount,
            )
            runCatching { templateRepository.importTemplate(content, resolution) }
                .onSuccess { imported ->
                    _uiState.update {
                        it.copy(
                            busy = false,
                            importPreview = null,
                            message = if (imported.replaced) {
                                "已替换模板「${imported.gameName}」；已有订单保持不变"
                            } else {
                                "已另存为「${imported.gameName}」；已有订单保持不变"
                            },
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            busy = false,
                            message = "导入失败，模板库没有变化：${error.message ?: "未知错误"}",
                        )
                    }
                }
        }
    }

    // ------------------------------------------------------------------ export

    /**
     * Writes exactly one template as UTF-8 JSON to the document the user chose. No owner,
     * account, order, receipt or credential is ever part of the file.
     */
    fun exportTo(uri: Uri, templateId: String) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val template = runCatching { templateRepository.findTemplate(templateId) }.getOrNull()
            if (template == null) {
                _uiState.update { it.copy(busy = false, message = "模板不存在，可能已被删除") }
                return@launch
            }
            val bytes = TemplateFileCodec.encode(template)
            runCatching { fileRepository.write(uri, bytes) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            busy = false,
                            message = "已导出「${template.gameName} · ${template.templateVersion}」",
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            busy = false,
                            message = "导出失败：${error.message ?: "无法写入所选位置"}",
                        )
                    }
                }
        }
    }

    // ------------------------------------------------------------------ delete

    fun requestDelete(template: GameTemplateSummary) = _uiState.update {
        it.copy(deleteCandidate = template)
    }

    fun dismissDelete() = _uiState.update { it.copy(deleteCandidate = null) }

    fun confirmDelete() {
        val candidate = _uiState.value.deleteCandidate ?: return
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, deleteCandidate = null) }
        viewModelScope.launch {
            runCatching { templateRepository.deleteTemplate(candidate.id) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            busy = false,
                            message = "已删除模板「${candidate.label}」；已有订单与历史记录不变",
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(busy = false, message = "删除失败：${error.message ?: "未知错误"}")
                    }
                }
        }
    }

    private fun TemplateFileContent.toPreview(
        fileName: String?,
        conflict: GameTemplateSummary?,
        saveAsName: String,
    ): TemplateImportPreview = TemplateImportPreview(
        fileName = fileName,
        templateId = templateId,
        draft = draft,
        categoryCount = categoryCount,
        taskCount = taskCount,
        conflict = conflict,
        saveAsName = saveAsName,
    )
}
