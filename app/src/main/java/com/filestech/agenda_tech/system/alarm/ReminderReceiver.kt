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
 * Work runs off the main thread via [goAsync] so the ~10 s broadcast budget is respected without
 * blocking.
 */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var handler: ReminderActionHandler

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (!handler.handles(action)) return
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        val occurrenceStart = intent.getLongExtra(EXTRA_OCCURRENCE_START, -1L)
        if (eventId < 0L) return

        val pendingResult = goAsync()
        scope.launch {
            try {
                handler.handle(action, reminderId, eventId, occurrenceStart)
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
