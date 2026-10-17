package com.daigan.record.feature.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daigan.record.data.model.AccountDraft
import com.daigan.record.data.model.AccountField
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.ui.components.PageHeader

@Composable
fun AccountFormScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountFormViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.completed) {
        if (uiState.completed) onDone()
    }

    AccountFormScreen(
        uiState = uiState,
        onBack = onDone,
        onDraftChange = viewModel::onDraftChange,
        onSelectLoginMethod = viewModel::selectLoginMethod,
        onSave = viewModel::save,
        modifier = modifier,
    )
}

@Composable
internal fun AccountFormScreen(
    uiState: AccountFormUiState,
    onBack: () -> Unit,
    onDraftChange: ((AccountDraft) -> AccountDraft) -> Unit,
    onSelectLoginMethod: (LoginMethod) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val draft = uiState.draft
    val errors = uiState.errors

    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = if (uiState.isEdit) "编辑游戏账号" else "新增游戏账号",
            subtitle = uiState.ownerNickname?.let { "所属老板：$it" },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = draft.gameName,
                onValueChange = { value -> onDraftChange { it.copy(gameName = value) } },
                label = { Text("游戏名称") },
                singleLine = true,
                isError = errors.containsKey(AccountField.GAME_NAME),
                supportingText = {
                    Text(errors[AccountField.GAME_NAME] ?: "模板系统将在第四阶段提供，当前可直接填写游戏名称。")
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.server,
                onValueChange = { value -> onDraftChange { it.copy(server = value) } },
                label = { Text("区服") },
                singleLine = true,
                isError = errors.containsKey(AccountField.SERVER),
                supportingText = { errors[AccountField.SERVER]?.let { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.uid,
                onValueChange = { value -> onDraftChange { it.copy(uid = value) } },
                label = { Text("游戏 UID") },
                singleLine = true,
                isError = errors.containsKey(AccountField.UID),
                supportingText = { errors[AccountField.UID]?.let { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.loginAccount,
                onValueChange = { value -> onDraftChange { it.copy(loginAccount = value) } },
                label = { Text("登录账号") },
                singleLine = true,
                isError = errors.containsKey(AccountField.LOGIN_ACCOUNT),
                supportingText = { errors[AccountField.LOGIN_ACCOUNT]?.let { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            Column(modifier = Modifier.selectableGroup()) {
                Text(text = "登录方式", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = draft.loginMethod == LoginMethod.PASSWORD,
                            onClick = { onSelectLoginMethod(LoginMethod.PASSWORD) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = draft.loginMethod == LoginMethod.PASSWORD,
                        onClick = null,
                    )
                    Text(text = "账号密码", modifier = Modifier.padding(start = 8.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = draft.loginMethod == LoginMethod.VERIFICATION_CODE,
                            onClick = { onSelectLoginMethod(LoginMethod.VERIFICATION_CODE) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = draft.loginMethod == LoginMethod.VERIFICATION_CODE,
                        onClick = null,
                    )
                    Text(text = "验证码登录", modifier = Modifier.padding(start = 8.dp))
                }
            }

            if (draft.loginMethod == LoginMethod.PASSWORD) {
                OutlinedTextField(
                    value = draft.password,
                    onValueChange = { value -> onDraftChange { it.copy(password = value) } },
                    label = { Text("登录密码") },
                    singleLine = true,
                    isError = errors.containsKey(AccountField.PASSWORD),
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                    supportingText = {
                        Text(
                            errors[AccountField.PASSWORD]
                                ?: "密码仅保存在本机应用数据库，不会写入日志或分析数据。",
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = "验证码登录不会保存验证码；切换到验证码方式会清除已保存的密码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (uiState.ownerArchived) {
                Text(
                    text = "老板已归档，账号只能查看；请先在归档记录中恢复老板。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (uiState.saveError != null) {
                Text(
                    text = uiState.saveError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = onSave,
                enabled = !uiState.isSaving && !uiState.ownerArchived,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text(if (uiState.isSaving) "保存中…" else "保存账号")
            }
        }
    }
}
