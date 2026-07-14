package com.filestech.agenda_tech.system.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.filestech.agenda_tech.di.ApplicationScope
import com.filestech.agenda_tech.domain.repository.EventRepository
import com.filestech.agenda_tech.system.notifications.ReminderNotifier
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Fires when a reminder alarm goes off: posts the notification for the occurrence and rolls the
 * reminder forward to the next occurrence (recurring events). Work runs off the main thread via
 * [goAsync] so the ~10 s broadcast budget is respected without blocking.
 */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var eventRepository: EventRepository
    @Inject lateinit var notifier: ReminderNotifier
    @Inject lateinit var scheduler: ReminderScheduler

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        val occurrenceStart = intent.getLongExtra(EXTRA_OCCURRENCE_START, -1L)
        if (eventId < 0L) return

        val pendingResult = goAsync()
        scope.launch {
            try {
                eventRepository.getById(eventId)?.let { event ->
                    notifier.postReminder(event, occurrenceStart)
                }
                if (reminderId >= 0L && occurrenceStart >= 0L) {
                    scheduler.onReminderFired(reminderId, eventId, occurrenceStart)
                }
            } catch (t: Throwable) {
                Timber.w(t, "ReminderReceiver: failed handling reminder %d", reminderId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.filestech.agenda_tech.action.REMINDER_FIRE"
        const val EXTRA_REMINDER_ID = "reminderId"
        const val EXTRA_EVENT_ID = "eventId"
        const val EXTRA_OCCURRENCE_START = "occurrenceStart"
    }
}
