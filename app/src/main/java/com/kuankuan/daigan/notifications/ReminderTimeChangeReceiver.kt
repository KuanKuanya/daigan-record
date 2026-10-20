package com.kuankuan.daigan.notifications

import com.kuankuan.daigan.data.repository.ApplicationReadiness
import kotlinx.coroutines.CancellationException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The reminder clock is device local time. When the user changes the time zone or the
 * wall clock, the already queued WorkManager delay no longer points at the configured
 * minute, so the schedule is rebuilt. WorkManager itself survives reboots, so no boot
 * receiver is needed for persistence.
 */
@AndroidEntryPoint
class ReminderTimeChangeReceiver : BroadcastReceiver() {

    @Inject
    lateinit var coordinator: ReminderCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_TIMEZONE_CHANGED && action != Intent.ACTION_TIME_CHANGED) {
            return
        }
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                ApplicationReadiness.awaitReady()
                coordinator.onDatasetReplaced()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Recovery errors keep the app locked; the next successful startup or
                // foreground transition reconciles from persisted preferences.
            } finally {
                pending.finish()
            }
        }
    }
}
