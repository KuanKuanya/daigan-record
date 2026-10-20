package com.kuankuan.daigan.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kuankuan.daigan.MainActivity
import com.kuankuan.daigan.R
import com.kuankuan.daigan.ui.navigation.DeepLink
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Posts reminder notifications and reports whether the user's settings allow it. */
interface ReminderNotifier {

    fun canNotify(): Boolean

    fun cancel(category: ReminderCategory) = Unit

    /** Returns false when notifications are not allowed; never throws to the worker. */
    fun post(message: ReminderMessage): Boolean

    /** System screen where a denied permission or a disabled channel can be changed. */
    fun notificationSettingsIntent(): Intent
}

@Singleton
class AndroidReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReminderNotifier {

    override fun cancel(category: ReminderCategory) {
        NotificationManagerCompat.from(context).cancel(notificationId(category))
    }

    override fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager?.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    override fun post(message: ReminderMessage): Boolean {
        if (!canNotify()) return false
        ensureChannel()
        val intent = Intent(context, MainActivity::class.java).apply {
            action = if (message.orderId == null) Intent.ACTION_MAIN else Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            message.orderId?.let { putExtra(DeepLink.EXTRA_ORDER_ID, it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId(message.category),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(message.title)
            .setContentText(message.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        // Keep the check adjacent to notify for both runtime revocation and lint.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return try {
            NotificationManagerCompat.from(context).notify(notificationId(message.category), notification)
            true
        } catch (_: SecurityException) {
            // Permission may be revoked between the check and the binder call.
            false
        }
    }

    override fun notificationSettingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "业务提醒",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "短期单截止、托管到期和未完成任务提醒"
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun notificationId(category: ReminderCategory): Int = 4100 + category.ordinal

    private companion object {
        const val CHANNEL_ID = "daigan-reminders"
    }
}
