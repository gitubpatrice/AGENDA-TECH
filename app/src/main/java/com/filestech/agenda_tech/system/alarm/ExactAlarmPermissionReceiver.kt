package com.filestech.agenda_tech.system.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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
 * ## Why the body refuses work instead of trusting the platform
 *
 * This KDoc used to claim the broadcast is protected — that only the system can send it — and that
 * exporting the receiver therefore opened nothing. **Measured on the Galaxy S9 (API 29), that is
 * false**, with a control to rule out the obvious objection:
 *
 * ```
 * am broadcast -a android.intent.action.BOOT_COMPLETED               -n …/BootReceiver
 *   → SecurityException: Permission Denial: not allowed to send broadcast … from uid=2000
 * am broadcast -a android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -n …/this
 *   → Broadcast completed: result=0
 * ```
 *
 * Same emitter, same uid, same channel, opposite outcomes. The protection is a `<protected-broadcast>`
 * entry in the platform's own manifest, and it is **versioned with the platform**: this action arrived
 * in API 31, so an API 26–30 image cannot list it. On those versions any app can reach this receiver
 * with an explicit intent and drive a full database open — SQLCipher, Keystore IPC, AES-GCM — plus a
 * whole reschedule pass, in a loop, for free.
 *
 * So the guard is in the body rather than in the manifest. The receiver stays exported, because the
 * system needs to reach it on API 31–32; what changes is that an impostor's broadcast now returns
 * before Hilt builds anything.
 */
@AndroidEntryPoint
class ExactAlarmPermissionReceiver : BroadcastReceiver() {

    /** Resolved inside the coroutine, never at injection time — see [rescheduleRemindersAsync]. */
    @Inject lateinit var scheduler: Provider<ReminderScheduler>

    /** Safe to inject directly: [ApplicationScope] only needs a dispatcher, never the database. */
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return
        // Outside API 31–32 this broadcast has no legitimate sender: it did not exist before 31, and
        // from 33 `USE_EXACT_ALARM` is granted without a dialogue and cannot be revoked, so the system
        // has nothing to announce. Anything arriving here is an impostor — and below 31 the platform
        // will not stop it, see the class KDoc.
        //
        // Written as two `<` / `>` guards rather than one `!in a..b`: the range form is the same
        // check, but lint cannot see through it to prove the API-31 call below is reachable only on
        // 31+. Shaping the code so the tool can check it beats suppressing the tool.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2) return
        // Second, cheaper filter for the versions where the sender IS the system: it announces a
        // *change*, so if exact alarms are still unavailable there is nothing to re-arm. It also
        // costs an impostor on 31–32 the whole reschedule pass, which is the only check available
        // here — a receiver cannot ask who sent it a broadcast.
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (alarmManager?.canScheduleExactAlarms() != true) return
        rescheduleRemindersAsync(scope, scheduler, reason = "exact-alarm permission changed")
    }
}
