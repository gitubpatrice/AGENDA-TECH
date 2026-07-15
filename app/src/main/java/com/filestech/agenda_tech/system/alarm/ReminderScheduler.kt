package com.filestech.agenda_tech.system.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.Reminder
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander
import com.filestech.agenda_tech.domain.reminder.ReminderScheduling
import com.filestech.agenda_tech.domain.repository.EventRepository
import com.filestech.agenda_tech.domain.repository.ReminderRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules and cancels the OS exact alarms that drive reminder notifications. The *when* is pure
 * ([ReminderScheduling]); this class owns the Android plumbing (AlarmManager + PendingIntent).
 *
 * Uses `setExactAndAllowWhileIdle` when exact alarms are permitted (the app declares
 * `USE_EXACT_ALARM`, auto-granted for a calendar app on Android 13+, or `SCHEDULE_EXACT_ALARM` on
 * 12), and degrades to `setAndAllowWhileIdle` (inexact, still Doze-friendly) otherwise so a reminder
 * is never silently dropped.
 *
 * After an alarm fires, [onReminderFired] rolls the reminder forward to the following occurrence, so
 * recurring reminders keep going without a running service.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmManager: AlarmManager,
    private val expander: RecurrenceExpander,
    private val eventRepository: EventRepository,
    private val reminderRepository: ReminderRepository,
) {

    /** (Re)schedule every reminder of one event — call after creating/editing it. */
    suspend fun rescheduleEvent(eventId: Long) {
        val event = eventRepository.getById(eventId) ?: return
        val now = System.currentTimeMillis()
        reminderRepository.getForEvent(eventId).forEach { reminder ->
            schedule(reminder, event, ReminderScheduling.initialEarliestStart(now, reminder.minutesBefore))
        }
    }

    /** Reschedule every reminder in the database — call once after a reboot. */
    suspend fun rescheduleAll() {
        val now = System.currentTimeMillis()
        val eventCache = HashMap<Long, Event?>()
        reminderRepository.getAll().forEach { reminder ->
            val event = eventCache.getOrPut(reminder.eventId) { eventRepository.getById(reminder.eventId) }
                ?: return@forEach
            schedule(reminder, event, ReminderScheduling.initialEarliestStart(now, reminder.minutesBefore))
        }
    }

    /** After an alarm fires, arm the next occurrence (recurring) or clear it (series ended). */
    suspend fun onReminderFired(reminderId: Long, eventId: Long, firedOccurrenceStartUtcMillis: Long) {
        val event = eventRepository.getById(eventId) ?: return
        val reminder = reminderRepository.getById(reminderId) ?: return
        schedule(reminder, event, ReminderScheduling.nextEarliestStart(firedOccurrenceStartUtcMillis))
    }

    /** Cancel the alarms of every reminder of an event — call before deleting the event. */
    suspend fun cancelEvent(eventId: Long) {
        reminderRepository.getForEvent(eventId).forEach { cancel(it.id) }
    }

    /**
     * Re-arm a reminder [SNOOZE_MINUTES] from now, for the same occurrence.
     *
     * Deliberately kept off the series' own alarm: it uses its own action and its own request-code
     * space. Re-using [ReminderReceiver.ACTION_FIRE] would make the snoozed alarm roll the series
     * forward a second time, and re-using the reminder's request code would overwrite the alarm that
     * [onReminderFired] has just armed for the next occurrence — the reminder after this one would
     * silently never fire.
     */
    fun snooze(eventId: Long, occurrenceStartUtcMillis: Long) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_SNOOZE_FIRE
            putExtra(ReminderReceiver.EXTRA_EVENT_ID, eventId)
            putExtra(ReminderReceiver.EXTRA_OCCURRENCE_START, occurrenceStartUtcMillis)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            snoozeRequestCode(eventId, occurrenceStartUtcMillis),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val fireAt = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pendingIntent)
        } else {
            Timber.i("ReminderScheduler: exact alarms unavailable — snoozing event %d inexactly", eventId)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pendingIntent)
        }
    }

    /**
     * Cancel alarms by reminder id.
     *
     * Exists for the restore path: [rescheduleAll] walks the reminders that *exist*, so it can never
     * disarm an alarm whose row a restore has just deleted. The ids have to be captured before the
     * wipe and handed back here, or a reminder from the replaced agenda keeps firing.
     */
    fun cancelReminders(reminderIds: Collection<Long>) = reminderIds.forEach(::cancel)

    private fun schedule(reminder: Reminder, event: Event, earliestOccurrenceStart: Long) {
        val fire = ReminderScheduling.computeNextFire(expander, event, reminder.minutesBefore, earliestOccurrenceStart)
        if (fire == null) {
            cancel(reminder.id)
            return
        }
        val pendingIntent = buildPendingIntent(
            reminderId = reminder.id,
            eventId = event.id,
            occurrenceStartUtcMillis = fire.occurrenceStartUtcMillis,
            allowCreate = true,
        ) ?: return
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fire.fireAtUtcMillis, pendingIntent)
        } else {
            Timber.i("ReminderScheduler: exact alarms unavailable — using inexact alarm for reminder %d", reminder.id)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fire.fireAtUtcMillis, pendingIntent)
        }
    }

    private fun cancel(reminderId: Long) {
        val pendingIntent = buildPendingIntent(reminderId, eventId = 0L, occurrenceStartUtcMillis = 0L, allowCreate = false)
            ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun buildPendingIntent(
        reminderId: Long,
        eventId: Long,
        occurrenceStartUtcMillis: Long,
        allowCreate: Boolean,
    ): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FIRE
            putExtra(ReminderReceiver.EXTRA_REMINDER_ID, reminderId)
            putExtra(ReminderReceiver.EXTRA_EVENT_ID, eventId)
            putExtra(ReminderReceiver.EXTRA_OCCURRENCE_START, occurrenceStartUtcMillis)
        }
        val flags = if (allowCreate) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        }
        return PendingIntent.getBroadcast(context, reminderId.toInt(), intent, flags)
    }

    companion object {
        /** How long "snooze" defers a reminder. Ten minutes: long enough to finish what you were doing. */
        const val SNOOZE_MINUTES = 10

        /**
         * Request code for a snoozed alarm — keyed by the occurrence, not by the reminder, so two
         * reminders on the same occurrence share one snooze instead of stacking notifications.
         * Separate from the reminder-id space [buildPendingIntent] uses; the differing action keeps the
         * two PendingIntents distinct even if the ints ever collided.
         */
        private fun snoozeRequestCode(eventId: Long, occurrenceStartUtcMillis: Long): Int =
            (eventId * 31 + occurrenceStartUtcMillis).hashCode()
    }
}
