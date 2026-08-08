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
import javax.inject.Provider

/**
 * Android entry point for the three moments of a reminder's life:
 *
 * - [ACTION_FIRE] — the alarm went off.
 * - [ACTION_SNOOZE] — the user tapped "snooze" on the notification.
 * - [ACTION_SNOOZE_FIRE] — a snoozed alarm went off.
 *
 * Deliberately thin: it unpacks the intent and hands over to [ReminderActionHandler], where the
 * decisions live — and where they can be tested. `@AndroidEntryPoint` makes `onReceive` itself
 * untestable off-device (it needs a real Application to inject), so nothing worth checking belongs
 * in here.
 *
 * ## Why the handler arrives through a [Provider] (audit F2)
 *
 * This KDoc used to claim "work runs off the main thread via `goAsync()` so the ~10 s broadcast budget
 * is respected without blocking". That was false for the most expensive part. `@Inject lateinit var` is
 * **eager**: Hilt sets it when it injects the receiver, on the **main thread**, before this body runs.
 * Resolving [ReminderActionHandler] pulls `EventRepository` → `EventDao` → `AppDatabase`, i.e.
 * `DatabaseFactory.build()` — native library load, Keystore IPC, AES-GCM decrypt, SQLCipher open — all
 * of it before `goAsync()` had extended anything.
 *
 * `MainActivity` (`3b4da6f`) and `MainApplication` (`6aafe38`) were both moved to a `Provider` for this
 * exact reason; the receivers were the asymmetric twins nobody had fixed. The action filter now goes
 * through `ReminderActionHandler.handles`, which lives in its companion precisely so an intent that is
 * none of our business costs nothing at all.
 */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    /** Resolved inside the coroutine, never at injection time — see the class KDoc. */
    @Inject lateinit var handler: Provider<ReminderActionHandler>

    /** Safe to inject directly: [ApplicationScope] only needs a dispatcher, never the database. */
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        // Companion call: filtering must not build the graph. See ReminderActionHandler.handles.
        if (!ReminderActionHandler.handles(action)) return
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        val occurrenceStart = intent.getLongExtra(EXTRA_OCCURRENCE_START, -1L)
        if (eventId < 0L) return

        val pendingResult = goAsync()
        scope.launch {
            try {
                handler.get().handle(action, reminderId, eventId, occurrenceStart)
            } catch (t: Throwable) {
                Timber.w(t, "ReminderReceiver: failed handling %s for event %d", action, eventId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.filestech.agenda_tech.action.REMINDER_FIRE"

        /** The user tapped "snooze" on a posted reminder. */
        const val ACTION_SNOOZE = "com.filestech.agenda_tech.action.REMINDER_SNOOZE"

        /** A snoozed alarm went off — re-post only, the series has already moved on. */
        const val ACTION_SNOOZE_FIRE = "com.filestech.agenda_tech.action.REMINDER_SNOOZE_FIRE"
        const val EXTRA_REMINDER_ID = "reminderId"
        const val EXTRA_EVENT_ID = "eventId"
        const val EXTRA_OCCURRENCE_START = "occurrenceStart"
    }
}
