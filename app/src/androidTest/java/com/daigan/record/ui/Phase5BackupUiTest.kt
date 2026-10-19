package com.daigan.record.ui

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.daigan.record.testing.awaitApplicationReady
import com.daigan.record.MainActivity
import com.daigan.record.backup.BackupExportService
import com.daigan.record.backup.BackupRestoreService
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.repository.ApplicationReadiness
import com.daigan.record.data.repository.OwnerRepository
import com.daigan.record.feature.settings.BackupScreen
import com.daigan.record.feature.settings.BackupViewModel
import com.daigan.record.feature.settings.RestoreStage
import com.daigan.record.ui.theme.DaiganRecordTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real Activity, Hilt ViewModel, archive services and Room; only the SAF picker is bypassed. */
@HiltAndroidTest
class Phase5BackupUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var database: RecordDatabase
    @Inject lateinit var owners: OwnerRepository
    @Inject lateinit var exporter: BackupExportService
    @Inject lateinit var restorer: BackupRestoreService
    private lateinit var viewModel: BackupViewModel
    private val files = mutableListOf<File>()

    @Before
    fun setup() {
        hilt.inject()
        ApplicationReadiness.ready()
        compose.awaitApplicationReady()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            compose.activity.applicationContext,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @After
    fun cleanup() {
        ApplicationReadiness.ready()
        runBlocking { restorer.discardSession() }
        files.forEach { it.delete() }
    }

    @Test
    fun startupRecoveryBlocksNormalScreensAndShowsFailureRetryUntilReady() {
        try {
            compose.runOnUiThread { ApplicationReadiness.begin() }
            compose.onNodeWithText("正在检查并恢复资料…").assertExists()
            compose.onNode(hasText("账号信息") and hasClickAction()).assertDoesNotExist()
            compose.onNodeWithContentDescription("设置").assertDoesNotExist()
            compose.runOnUiThread { ApplicationReadiness.fail() }
            compose.onNodeWithText("资料恢复尚未完成，当前资料已暂时锁定。请重试；原恢复文件会保留。").assertExists()
            compose.onNodeWithText("重试恢复").assertIsEnabled()
            compose.onNode(hasText("账号信息") and hasClickAction()).assertDoesNotExist()
            compose.runOnUiThread { ApplicationReadiness.ready() }
            compose.onNode(hasText("账号信息") and hasClickAction()).assertExists()
            compose.onNodeWithText("重试恢复").assertDoesNotExist()
        } finally {
            ApplicationReadiness.ready()
        }
    }

    @Test
    fun exportFormSwitchesBetweenPlainAndEncryptedAndRejectsPasswordMismatch() {
        showBackupScreen()
        compose.onNodeWithTag("backup-password-confirm").assertDoesNotExist()
        compose.onNodeWithText("选择位置并导出（不加密）").assertExists()
        compose.onNodeWithTag("backup-password").performTextInput("secret-123")
        compose.onNodeWithTag("backup-password-confirm").performScrollTo().performTextInput("different")
        compose.onNodeWithText("选择位置并导出（加密）").assertExists()
        val target = backupFile("mismatch")
        compose.runOnUiThread { viewModel.export(Uri.fromFile(target)) }
        compose.onNodeWithText("两次输入的密码不一致。").performScrollTo().assertExists()
        assertFalse(target.exists())
        assertFalse(viewModel.uiState.value.exportRunning)
        compose.onNodeWithTag("backup-password").performScrollTo().performTextClearance()
        compose.onNodeWithTag("backup-password-confirm").assertDoesNotExist()
        compose.onNodeWithText("选择位置并导出（不加密）").assertExists()
    }

    @Test
    fun ordinaryBackupPreviewContainsRealDataAndCancelKeepsCurrentDataset() {
        val target = backupFile("ordinary")
        runBlocking {
            owners.createOwner("备份里的老板")
            exporter.export(Uri.fromFile(target), null)
            owners.createOwner("备份之后新增")
        }
        showBackupScreen()
        compose.runOnUiThread { viewModel.inspect(Uri.fromFile(target)) }
        awaitStage { it is RestoreStage.Ready }
        compose.onNodeWithTag("restore-preview").performScrollTo().assertExists()
        compose.onNodeWithText("1 位老板 · 0 个游戏账号 · 0 笔订单").performScrollTo().assertExists()
        assertEquals(2, runBlocking { database.ownerDao().findAll().size })
        compose.onNodeWithTag("restore-cancel").performScrollTo().performClick()
        awaitStage { it is RestoreStage.Idle }
        compose.onNodeWithTag("restore-preview").assertDoesNotExist()
        assertEquals(setOf("备份里的老板", "备份之后新增"), runBlocking {
            database.ownerDao().findAll().map { it.nickname }.toSet()
        })
    }

    @Test
    fun encryptedExportPasswordUnlocksPreviewAndOnlyConfirmationReplacesData() {
        runBlocking { owners.createOwner("加密备份老板") }
        val target = backupFile("encrypted")
        showBackupScreen()
        compose.onNodeWithTag("backup-password").performTextInput("secret-123")
        compose.onNodeWithTag("backup-password-confirm").performScrollTo().performTextInput("secret-123")
        compose.runOnUiThread { viewModel.export(Uri.fromFile(target)) }
        compose.waitUntil(20_000) { viewModel.uiState.value.exportResult != null || viewModel.uiState.value.exportError != null }
        assertEquals(null, viewModel.uiState.value.exportError)
        assertTrue(viewModel.uiState.value.exportResult!!.encrypted)
        assertEquals(target.name, viewModel.uiState.value.exportResult!!.fileName)
        compose.onNodeWithText("最近一次导出成功").performScrollTo().assertExists()
        runBlocking { owners.createOwner("必须经确认才删除") }
        compose.runOnUiThread { viewModel.inspect(Uri.fromFile(target)) }
        awaitStage { it is RestoreStage.NeedsPassword }
        compose.onNodeWithTag("restore-password").performScrollTo().performTextInput("secret-123")
        assertEquals("secret-123", viewModel.uiState.value.passwordInput)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        compose.onNodeWithTag("restore-password-submit").performScrollTo().assertIsDisplayed()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        awaitStage { it is RestoreStage.Ready }
        compose.onNodeWithTag("restore-preview").performScrollTo().assertExists()
        compose.onNodeWithText("1 位老板 · 0 个游戏账号 · 0 笔订单").performScrollTo().assertExists()
        assertEquals(2, runBlocking { database.ownerDao().findAll().size })
        // Exercise the accessibility click after scrolling; physical picker/touch flow is checked separately.
        compose.onNodeWithTag("restore-confirm").performScrollTo().assertIsDisplayed()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        awaitStage { it is RestoreStage.Success || it is RestoreStage.Failed }
        assertTrue(viewModel.uiState.value.restoreStage.toString(), viewModel.uiState.value.restoreStage is RestoreStage.Success)
        compose.onNodeWithTag("restore-success").performScrollTo().assertExists()
        compose.onNodeWithTag("restore-finished").performScrollTo().assertExists()
        assertEquals(listOf("加密备份老板"), runBlocking { database.ownerDao().findAll().map { it.nickname } })
    }

    private fun showBackupScreen() {
        compose.runOnUiThread {
            viewModel = ViewModelProvider(compose.activity)[BackupViewModel::class.java]
            compose.activity.setContent {
                DaiganRecordTheme { BackupScreen(onBack = {}, onRestoreFinished = {}, viewModel = viewModel) }
            }
        }
        compose.onNodeWithTag("backup-password").assertExists()
    }

    private fun awaitStage(predicate: (RestoreStage) -> Boolean) {
        try {
            compose.waitUntil(20_000) {
                predicate(viewModel.uiState.value.restoreStage) || viewModel.uiState.value.restoreStage is RestoreStage.Failed
            }
        } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Restore did not advance; state=${viewModel.uiState.value.restoreStage}; error=${viewModel.uiState.value.restoreError}; passwordLength=${viewModel.uiState.value.passwordInput.length}", error)
        }
        assertTrue("Unexpected restore state: ${viewModel.uiState.value.restoreStage}",
            predicate(viewModel.uiState.value.restoreStage))
    }

    private fun backupFile(name: String): File = File(
        compose.activity.cacheDir, "phase5-ui-$name-${System.nanoTime()}.dgbak",
    ).also { files += it }
}
