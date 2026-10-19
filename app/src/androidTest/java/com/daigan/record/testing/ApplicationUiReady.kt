package com.daigan.record.testing

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import com.daigan.record.MainActivity
import org.junit.rules.TestRule

/** Wait for real asynchronous startup identity/recovery instead of a fixed sleep. */
fun <R : TestRule> AndroidComposeTestRule<R, MainActivity>.awaitApplicationReady() {
    waitUntil(10_000) {
        onAllNodes(hasText("账号信息") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
    }
}
