package com.kuankuan.daigan.notifications

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.kuankuan.daigan.testing.awaitApplicationReady
import com.kuankuan.daigan.MainActivity
import com.kuankuan.daigan.ui.navigation.DeepLink
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class ReminderNavigationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Before fun setup() { hilt.inject(); compose.awaitApplicationReady() }

    @Test fun settingsHasThreePersistedControlsAndBackNavigation() {
        compose.onNode(hasText("账号信息") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("提醒设置").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("reminder-switch-short-deadline").fetchSemanticsNodes().isNotEmpty()
        }
        ReminderCategory.entries.forEach { category ->
            compose.onNodeWithTag("reminder-switch-${category.key}").performScrollTo().assertIsOff()
        }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("备份与恢复").assertExists()
    }

    @Test fun notificationWithStaleOrderIdShowsMissingOrderThenAggregateReturnsToToday() {
        val activity = compose.activity
        val originalIntent = Intent(activity.intent)
        try {
        compose.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(DeepLink.EXTRA_ORDER_ID, "missing-after-restore")
            })
        }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("找不到该订单").fetchSemanticsNodes().isNotEmpty()
        }
        compose.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("托管打卡").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("短期任务").assertExists()
        } finally {
            // ActivityScenario matches lifecycle events against its original launch Intent.
            // Production onNewIntent intentionally keeps the notification Intent for recreation.
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activity.intent = originalIntent
            }
        }
    }
}
