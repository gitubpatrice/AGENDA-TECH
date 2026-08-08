package com.filestech.agenda_tech.system.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.VisibleForTesting
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.Reminder
import com.filestech.agenda_tech.domain.recurrence.ExpansionBudget
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander
import com.filestech.agenda_tech.domain.reminder.ReminderScheduling
import com.filestech.agenda_tech.domain.repository.EventRepository
import com.filestech.agenda_tech.domain.repository.ReminderRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
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

    /**
     * The budget [rescheduleAll] gives its pass. A `@VisibleForTesting` seam, as in [AppLockManager]:
     * the real ceiling is a million iterations, which no test can reach in reasonable time, and the
     * behaviour that has to be pinned is precisely what happens **once it is spent**.
     */
    @VisibleForTesting
    internal var newPassBudget: () -> ExpansionBudget = { ExpansionBudget() }

    /** (Re)schedule every reminder of one event — call after creating/editing it. */
    suspend fun rescheduleEvent(eventId: Long) {
        val event = eventRepository.getById(eventId) ?: return
        val now = System.currentTimeMillis()
        val excluded = excludedStartsFor(event)
        reminderRepository.getForEvent(eventId).forEach { reminder ->
            schedule(
                reminder,
                event,
                ReminderScheduling.initialEarliestStart(now, reminder.minutesBefore),
                excluded,
            )
        }
    }

    /**
     * Reschedule every reminder in the database — call once after a reboot.
     *
     * Audit F5 — a bounded [ExpansionBudget]. Exact alarms do not survive a reboot, so this runs inside
     * a broadcast whose budget is measured in seconds; without a ceiling, N reminders cost
     * `N × RecurrenceExpander.MAX_SCAN_ITERATIONS`, and N is exactly what an import controls. Running out
     * of time here means the process is killed with the reminders never re-armed — and nothing anywhere
     * says so.
     *
     * Audit F5-ter, found by external review — **the allowance is divided, not shared.** F5 passed one
     * instance across the pass, and F5-bis then taught the caller not to cancel when it ran out. Both
     * were right about a render, where the tail is merely not drawn this frame, and both were wrong
     * here: the sentence three lines above says exact alarms do not survive a reboot, so on this path
     * "left as it is" means *left unarmed, permanently* — until the user happens to edit the event.
     * One pathological series therefore silently cost every recurring reminder behind it.
     *
     * Splitting keeps F5's total ceiling exactly (`ways × total/ways ≤ total`) and removes the
     * starvation: a series can spend its own share and nobody else's. Non-recurring reminders never
     * enter this arithmetic at all — [RecurrenceExpander.nextOccurrenceStart] answers them before it
     * looks at a budget — so a hostile import cannot stop a plain reminder from being re-armed.
     *
     * What remains, stated rather than implied: a recurring series that outruns its own share is left
     * unarmed by this pass, and logged. That is the residue of the trade, not an oversight.
     */
    suspend fun rescheduleAll() {
        val now = System.currentTimeMillis()
        val overrides = eventRepository.observeOverrides().first()
        val excludedByParent = overrides
            .groupBy { it.recurrenceParentId }
            .mapValues { (_, list) -> list.mapNotNull { it.originalStartUtcMillis }.toHashSet() }
        val eventCache = HashMap<Long, Event?>()
        val reminders = reminderRepository.getAll()
        // Sized on the whole list rather than on the recurring subset: telling them apart first would
        // cost a second lookup of every event, and over-dividing only makes each share smaller than it
        // strictly had to be — never larger than the pass total, which is the property that matters.
        val share = newPassBudget().shareSize(reminders.size)
        reminders.forEach { reminder ->
            val event = eventCache.getOrPut(reminder.eventId) { eventRepository.getById(reminder.eventId) }
                ?: return@forEach
            val excluded = if (event.isRecurring) excludedByParent[event.id].orEmpty() else emptySet()
            schedule(
                reminder,
                event,
                ReminderScheduling.initialEarliestStart(now, reminder.minutesBefore),
                excluded,
                ExpansionBudget(share),
            )
        }
    }

    /** After an alarm fires, arm the next occurrence (recurring) or clear it (series ended). */
    suspend fun onReminderFired(reminderId: Long, eventId: Long, firedOccurrenceStartUtcMillis: Long) {
        val event = eventRepository.getById(eventId) ?: return
        val reminder = reminderRepository.getById(reminderId) ?: return
        schedule(
            reminder,
            event,
            ReminderScheduling.nextEarliestStart(firedOccurrenceStartUtcMillis),
            excludedStartsFor(event),
        )
    }

    /**
     * The instants of [event] that per-occurrence overrides have replaced, read from the live override
     * rows — the same mechanism the calendar views and search use.
     *
     * The scheduler used to read nothing here and rely on the `EXDATE`s persisted on the master alone,
     * which made it the only reader with its own answer to "does this occurrence still exist". The
     * editor writes both halves in one transaction (`upsertOverrideAtomic`), so in normal use the two
     * agree; a hand-edited `.atbak` is not required to carry the master's `EXDATE`, and there the
     * reminder would fire for an occurrence the user had moved away.
     */
    private suspend fun excludedStartsFor(event: Event): Set<Long> =
        if (!event.isRecurring) {
            emptySet()
        } else {
            eventRepository.observeOverrides().first()
                .filter { it.recurrenceParentId == event.id }
                .mapNotNullTo(HashSet()) { it.originalStartUtcMillis }
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

    private fun schedule(
        reminder: Reminder,
        event: Event,
        earliestOccurrenceStart: Long,
        extraExcludedStarts: Set<Long>,
        budget: ExpansionBudget? = null,
    ) {
        val fire = ReminderScheduling.computeNextFire(
            expander,
            event,
            reminder.minutesBefore,
            earliestOccurrenceStart,
            extraExcludedStarts,
            budget,
        )
        if (fire == null) {
            // Audit F5-bis, found by external review of lot D — and introduced BY lot D.
            //
            // `null` used to mean one thing: the series has no occurrence left, so the alarm should
            // go. Adding a budget gave it a second meaning — "we stopped looking" — without teaching
            // this caller to tell them apart. With a budget SHARED across the whole pass, exhausting
            // it on one pathological series made `computeNextFire` return null for every reminder
            // after it, and each of those was then CANCELLED. A reboot could therefore silently
            // disarm the rest of the user's reminders, permanently: nothing re-arms them until the
            // event is edited.
            //
            // That is the opposite of the trade the budget was introduced for. Truncating a
            // pathological tail means leaving those alarms alone, not destroying them. When the
            // budget is spent we cannot conclude anything about the series, so we conclude nothing.
            //
            // Audit F5-ter — "left as it is" was accurate on the edit path and misleading on the
            // reboot path, where there is no alarm left to leave: exact alarms do not survive a
            // reboot. The starvation that made this reachable for a whole tail of reminders is gone
            // (see `rescheduleAll`, the allowance is now divided), so what lands here is one series
            // that outran its own share. It stays unarmed until its event is next saved, and the log
            // says that rather than something reassuring.
            if (budget?.isExhausted == true) {
                Timber.w(
                    "ReminderScheduler: reminder %d outran its expansion share — not cancelled, but " +
                        "not armed either; it will not fire until its event is saved again",
                    reminder.id,
                )
                return
            }
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
