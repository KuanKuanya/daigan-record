package com.kuankuan.daigan.notifications

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderPermissionTest {
    @Test fun systemBlockedNotificationsPreventPostingAndOfferAppSettings() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val context: Context = ApplicationProvider.getApplicationContext()
        fun shell(command: String): String =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { String(it.readBytes()) }
            }
        // Changing the app-op exercises the real system notification gate without
        // pm revoke killing this instrumentation process along with its target uid.
        val previous = shell("cmd appops get ${context.packageName} POST_NOTIFICATION")
        val mode = Regex("POST_NOTIFICATION: (allow|ignore|deny|default|foreground)").find(previous)?.groupValues?.get(1) ?: "default"
        try {
            shell("cmd appops set ${context.packageName} POST_NOTIFICATION ignore")
            val notifier = AndroidReminderNotifier(context)
            assertFalse(notifier.canNotify())
            assertFalse(notifier.post(ReminderMessage(ReminderCategory.SHORT_DEADLINE, "提醒", "打开查看", "missing")))
            assertEquals(context.packageName, notifier.notificationSettingsIntent().getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))
        } finally {
            shell("cmd appops set ${context.packageName} POST_NOTIFICATION $mode")
        }
    }
}
