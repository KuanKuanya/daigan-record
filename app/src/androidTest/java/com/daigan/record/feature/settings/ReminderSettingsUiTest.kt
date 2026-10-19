package com.daigan.record.feature.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.daigan.record.notifications.ReminderCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReminderSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun validSaveReturnsOnlyTheSelectedCategoryAndMinuteValue() {
        val saved = mutableListOf<Pair<ReminderCategory, Int>>()
        compose.setContent {
            ReminderTimeInputDialog(ReminderCategory.HOSTING_EXPIRY, 1020, {},
                onSave = { category, minute -> saved += category to minute })
        }
        compose.onNodeWithTag("reminder-time-input").performTextClearance()
        compose.onNodeWithTag("reminder-time-input").performTextInput("2359")
        compose.onNodeWithTag("reminder-time-save").performClick()
        compose.runOnIdle {
            assertEquals(listOf(ReminderCategory.HOSTING_EXPIRY to 1439), saved)
        }
    }

    @Test
    fun invalidTimeCannotBeSaved() {
        val saved = mutableListOf<Int>()
        compose.setContent {
            ReminderTimeInputDialog(ReminderCategory.SHORT_DEADLINE, 1200, {},
                onSave = { _, minute -> saved += minute })
        }
        compose.onNodeWithTag("reminder-time-input").performTextClearance()
        compose.onNodeWithTag("reminder-time-input").performTextInput("2460")
        compose.onNodeWithTag("reminder-time-save").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
    }

    @Test
    fun cancelDoesNotPersistAnEditedTime() {
        val saved = mutableListOf<Int>()
        val visible = mutableStateOf(true)
        compose.setContent {
            if (visible.value) {
                ReminderTimeInputDialog(ReminderCategory.UNFINISHED_TASKS, 1260,
                    onDismiss = { visible.value = false },
                    onSave = { _, minute -> saved += minute })
            }
        }
        compose.onNodeWithTag("reminder-time-input").performTextClearance()
        compose.onNodeWithTag("reminder-time-input").performTextInput("0730")
        compose.onNodeWithTag("reminder-time-cancel").performClick()
        compose.runOnIdle {
            assertTrue(saved.isEmpty())
            assertTrue(!visible.value)
        }
    }
}
