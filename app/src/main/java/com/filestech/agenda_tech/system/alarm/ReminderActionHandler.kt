package com.filestech.agenda_tech.system.alarm

import com.filestech.agenda_tech.domain.repository.EventRepository
import com.filestech.agenda_tech.system.notifications.ReminderNotifier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a reminder alarm or notification button actually *does*, kept out of [ReminderReceiver].
 *
 * The receiver is a `@AndroidEntryPoint`, so its `onReceive` needs a real Application to inject and
 * cannot be exercised off-device. That left the one part worth testing — deciding which action does
 * what — with no net, and the difference between the actions is invisible at a glance: two of the
 * three post the very same notification. What separates them is what they must *not* do.
 */
@Singleton
class ReminderActionHandler @Inject constructor(
    private val eventRepository: EventRepository,
    private val notifier: ReminderNotifier,
    private val scheduler: ReminderScheduler,
) {

    /** True when [action] is one this handler owns — lets the receiver drop anything else early. */
    fun handles(action: String?): Boolean =
        action == ReminderReceiver.ACTION_FIRE ||
            action == ReminderReceiver.ACTION_SNOOZE ||
            action == ReminderReceiver.ACTION_SNOOZE_FIRE

    suspend fun handle(action: String?, reminderId: Long, eventId: Long, occurrenceStartUtcMillis: Long) {
        if (!handles(action) || eventId < 0L) return
        when (action) {
            ReminderReceiver.ACTION_SNOOZE -> {
                // Dismiss before re-arming: leaving the old notification up would show the same
                // reminder twice within the snooze window.
                notifier.cancelReminder(eventId, occurrenceStartUtcMillis)
                scheduler.snooze(eventId, occurrenceStartUtcMillis)
            }
            else -> {
                eventRepository.getById(eventId)?.let { event ->
                    notifier.postReminder(event, occurrenceStartUtcMillis)
                }
                // Only the series' own alarm rolls it forward. A snoozed one doing it again would
                // skip the occurrence after this one — silently, with no error anywhere.
                if (action == ReminderReceiver.ACTION_FIRE && reminderId >= 0L && occurrenceStartUtcMillis >= 0L) {
                    scheduler.onReminderFired(reminderId, eventId, occurrenceStartUtcMillis)
                }
            }
        }
    }
}
