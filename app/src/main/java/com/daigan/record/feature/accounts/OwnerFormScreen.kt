package com.daigan.record.feature.accounts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daigan.record.data.model.OwnerValidation
import com.daigan.record.ui.components.PageHeader

@Composable
fun OwnerFormScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OwnerFormViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.completed) {
        if (uiState.completed) onDone()
    }

    OwnerFormScreen(
        uiState = uiState,
        onBack = onDone,
        onNicknameChange = viewModel::onNicknameChange,
        onSave = viewModel::save,
        modifier = modifier,
    )
}

@Composable
internal fun OwnerFormScreen(
    uiState: OwnerFormUiState,
    onBack: () -> Unit,
    onNicknameChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = if (uiState.isEdit) "编辑老板" else "新增老板",
            subtitle = if (uiState.isEdit) "编号固定，昵称可修改" else "编号将在保存后自动生成",
            onBack = onBack,
        )
        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            return@Column
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = uiState.numberLabel,
                onValueChange = {},
                readOnly = true,
                enabled = false,
                label = { Text("老板编号") },
                supportingText = { Text("编号由系统自动分配，保存后不能修改。") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = uiState.nickname,
                onValueChange = onNicknameChange,
                label = { Text("老板昵称") },
                singleLine = true,
                isError = uiState.nicknameError != null,
                supportingText = {
                    Text(
                        uiState.nicknameError
                            ?: "最多 ${OwnerValidation.MAX_NICKNAME_LENGTH} 个字符，可随时修改。",
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            )
            if (uiState.saveError != null) {
                Text(
                    text = uiState.saveError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Button(
                onClick = onSave,
                enabled = !uiState.isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
            ) {
                Text(if (uiState.isSaving) "保存中…" else "保存")
            }
        }
    }
}
