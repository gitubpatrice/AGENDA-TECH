package com.filestech.agenda_tech.system.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.filestech.agenda_tech.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Provider

/**
 * Re-arms every reminder when the user grants the exact-alarm permission back.
 *
 * ## Why this exists, and what it is NOT
 *
 * Audit F12, and it is deliberately filed as a **no-regret defence** rather than as the correction of
 * an established defect. On Android 12 (API 31-32) the user can revoke `SCHEDULE_EXACT_ALARM` from
 * Settings; the documented consequence is that the app's pending exact alarms are dropped. What could
 * not be established — and could not be tested, since no API 31-32 device was available — is whether
 * the app is left with silently dead reminders after the permission comes back, because the scheduler
 * degrades to inexact alarms when the permission is missing and nothing re-promotes them afterwards.
 *
 * One external reviewer was confident it happens, another said it did not know. Rather than write a
 * correction for a defect nobody could reproduce, this listens for the one signal Android sends when
 * the permission is granted and does the thing that is right either way. If the alarms had survived,
 * re-arming them is a no-op costing one background pass; if they had not, this is what brings them
 * back.
 *
 * `USE_EXACT_ALARM` covers API 33+, where it is granted without a dialogue and cannot be revoked, so
 * in practice this only ever fires on Android 12.
 *
 * The broadcast is protected — only the system can send it — which is why the receiver is exported
 * without a permission of its own: no other app can reach it.
 */
@AndroidEntryPoint
class ExactAlarmPermissionReceiver : BroadcastReceiver() {

    /** Resolved inside the coroutine, never at injection time — see [rescheduleRemindersAsync]. */
    @Inject lateinit var scheduler: Provider<ReminderScheduler>

    /** Safe to inject directly: [ApplicationScope] only needs a dispatcher, never the database. */
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return
        rescheduleRemindersAsync(scope, scheduler, reason = "exact-alarm permission changed")
    }
}
