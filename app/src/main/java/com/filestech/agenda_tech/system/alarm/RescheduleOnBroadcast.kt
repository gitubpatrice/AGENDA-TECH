package com.filestech.agenda_tech.system.alarm

import android.content.BroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Provider

/**
 * Re-arms every reminder alarm, off the main thread, from inside a [BroadcastReceiver].
 *
 * Two receivers need exactly this — [BootReceiver] and [ExactAlarmPermissionReceiver] — and the part
 * they share is the part that is easy to get subtly wrong:
 *
 * - the scheduler arrives as a [Provider] and is resolved **inside** the coroutine. `@Inject lateinit
 *   var` is eager: Hilt sets the field while injecting the receiver, on the main thread, before
 *   `onReceive`'s own body runs. Resolving [ReminderScheduler] opens the SQLCipher database — native
 *   library, Keystore IPC, AES-GCM decrypt — and that is not something to do there (audit F2).
 * - `goAsync()` is taken **before** the coroutine starts and finished in a `finally`. Missing the
 *   `finally` on a failure path leaks the broadcast until the system reclaims it.
 *
 * Copying those two rules into a second receiver is how they drift apart, and a receiver where they
 * had drifted would fail the way the original did: silently, with every reminder gone and nothing
 * anywhere saying so.
 */
internal fun BroadcastReceiver.rescheduleRemindersAsync(
    scope: CoroutineScope,
    scheduler: Provider<ReminderScheduler>,
    reason: String,
) {
    val pendingResult = goAsync()
    scope.launch {
        try {
            scheduler.get().rescheduleAll()
            Timber.i("Reminders rescheduled (%s)", reason)
        } catch (t: Throwable) {
            Timber.w(t, "Failed to reschedule reminders (%s)", reason)
        } finally {
            pendingResult.finish()
        }
    }
}
