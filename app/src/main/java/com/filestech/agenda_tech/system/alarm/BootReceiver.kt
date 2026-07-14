package com.filestech.agenda_tech.system.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.filestech.agenda_tech.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Reschedules every reminder alarm after a reboot — exact alarms do not survive one. Fires on
 * `BOOT_COMPLETED`, which is delivered after the user unlocks (the SQLCipher database lives in
 * credential-encrypted storage and is only readable then).
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: ReminderScheduler

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        scope.launch {
            try {
                scheduler.rescheduleAll()
                Timber.i("BootReceiver: reminders rescheduled after boot")
            } catch (t: Throwable) {
                Timber.w(t, "BootReceiver: failed to reschedule reminders")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
