package com.daigan.record.ui

import androidx.test.espresso.Espresso
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.daigan.record.testing.awaitApplicationReady
import com.daigan.record.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The template library and editor are real screens backed by Room. The Hilt test module
 * supplies an in-memory database; the shipped asset seeds the generic built-in template.
 */
@HiltAndroidTest
class TemplateLibraryUiTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
        composeTestRule.awaitApplicationReady()
        openLibrary()
    }

    @Test
    fun libraryListsOnlyTheGenericBuiltInAndKeepsImportAvailable() {
        waitForTemplate("通用模板")
        assertTrue(composeTestRule.onAllNodesWithText("重返未来：1999").fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("鸣潮").fetchSemanticsNodes().isEmpty())
        composeTestRule.onNodeWithTag("template-library-import").assertExists()
        assertTrue(
            composeTestRule.onAllNodesWithText("内置").fetchSemanticsNodes().size == 1,
        )
        assertTrue(composeTestRule.onAllNodesWithText("模板只作为新建订单时的默认值", substring = true)
            .fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun creatingATemplateSavesAndReturnsItToTheLibrary() {
        waitForTemplate("通用模板")
        composeTestRule.onNodeWithTag("template-library-new").performClick()
        waitForTag("template-game-name")

        composeTestRule.onNodeWithTag("template-game-name").performTextClearance()
        composeTestRule.onNodeWithTag("template-game-name").performTextInput("我的游戏模板")
        scrollEditorTo("template-category-0-name")
        composeTestRule.onNodeWithTag("template-category-0-name").performTextInput("日常")
        scrollEditorTo("template-task-0-0-title")
        composeTestRule.onNodeWithTag("template-task-0-0-title").performTextInput("每日签到")
        Espresso.closeSoftKeyboard()
        scrollEditorTo("template-save")
        composeTestRule.onNodeWithTag("template-save").performClick()

        // Saving returns to the library. The new card is below the fold in a LazyColumn,
        // so scroll the list to it before asserting.
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithTag("template-library-list")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("template-library-list")
            .performScrollToNode(hasText("我的游戏模板"))
        composeTestRule.onNodeWithText("我的游戏模板").assertExists()
    }

    @Test
    fun savingAnEmptyTemplateShowsValidationErrors() {
        waitForTemplate("通用模板")
        composeTestRule.onNodeWithTag("template-library-new").performClick()
        waitForTag("template-game-name")
        composeTestRule.onNodeWithTag("template-game-name").performTextClearance()

        Espresso.closeSoftKeyboard()
        scrollEditorTo("template-save")
        composeTestRule.onNodeWithTag("template-save").performClick()

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("请填写游戏名称")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            composeTestRule.onAllNodesWithText("请填写游戏名称")
                .fetchSemanticsNodes().isNotEmpty(),
        )
        // The editor stays open: no template was written.
        composeTestRule.onNodeWithTag("template-editor").assertExists()
    }

    @Test
    fun editingABuiltInKeepsItsLibraryEntryAndShowsTheNewName() {
        waitForTemplate("通用模板")
        // Built-in ids come from the shipped asset, so the third card is addressed
        // explicitly instead of by an order-dependent text index.
        composeTestRule.onNodeWithTag("template-library-list")
            .performScrollToNode(hasTestTag("template-edit-tpl-generic"))
        composeTestRule.onNodeWithTag("template-edit-tpl-generic").performClick()
        waitForTag("template-game-name")

        composeTestRule.onNodeWithTag("template-game-name").performTextClearance()
        composeTestRule.onNodeWithTag("template-game-name").performTextInput("改过的通用模板")
        Espresso.closeSoftKeyboard()
        scrollEditorTo("template-save")
        composeTestRule.onNodeWithTag("template-save").performClick()

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithTag("template-library-list")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("template-library-list")
            .performScrollToNode(hasText("改过的通用模板"))
        composeTestRule.onNodeWithText("改过的通用模板").assertExists()
    }

    @Test
    fun cancellationKeepsTheLibraryUnchanged() {
        waitForTemplate("通用模板")
        composeTestRule.onNodeWithTag("template-library-new").performClick()
        waitForTag("template-game-name")
        composeTestRule.onNodeWithTag("template-game-name").performTextInput("不会保存")
        Espresso.closeSoftKeyboard()

        scrollEditorTo("template-cancel")
        composeTestRule.onNodeWithTag("template-cancel").performClick()

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("放弃这次修改？")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("template-discard-confirm").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("通用模板")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            composeTestRule.onAllNodesWithText("不会保存").fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun openLibrary() {
        composeTestRule.onNodeWithText("账号信息").performClick()
        composeTestRule.onNodeWithContentDescription("设置").performClick()
        composeTestRule.onNodeWithText("游戏模板").performClick()
        composeTestRule.onNodeWithTag("template-library").assertExists()
    }

    private fun waitForTemplate(name: String) {
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTag(tag: String) {
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** LazyColumn items are not composed until scrolled into view. */
    private fun scrollEditorTo(tag: String) {
        composeTestRule.onNodeWithTag("template-editor-list")
            .performScrollToNode(hasTestTag(tag))
    }
}
