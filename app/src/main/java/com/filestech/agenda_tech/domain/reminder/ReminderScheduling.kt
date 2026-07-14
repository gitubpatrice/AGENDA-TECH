package com.filestech.agenda_tech.domain.reminder

import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander

/** When and for which occurrence a reminder alarm should next fire. */
data class ScheduledFire(
    val fireAtUtcMillis: Long,
    val occurrenceStartUtcMillis: Long,
)

/**
 * Pure reminder-scheduling policy — no Android, fully unit-testable. Decides the next fire time for
 * a reminder given the event's occurrences (via [RecurrenceExpander]) and the current instant.
 *
 * A reminder fires [minutesBefore] an occurrence's start. To place the alarm in the future we look
 * for the earliest occurrence whose start is at or after `now + minutesBefore`, so its fire time
 * (`start − minutesBefore`) is ≥ now. After an alarm fires, the receiver reschedules using
 * [nextEarliestStart] (strictly past the occurrence that just fired) so a recurring reminder rolls
 * forward without re-firing the same instant.
 */
object ReminderScheduling {

    private const val MS_PER_MINUTE = 60_000L

    /** Earliest occurrence start to consider for the *initial* schedule, so the alarm is not in the past. */
    fun initialEarliestStart(nowUtcMillis: Long, minutesBefore: Int): Long =
        nowUtcMillis + minutesBefore * MS_PER_MINUTE

    /** Earliest occurrence start to consider when rescheduling after a fire — strictly after it. */
    fun nextEarliestStart(firedOccurrenceStartUtcMillis: Long): Long =
        firedOccurrenceStartUtcMillis + 1

    /**
     * The next fire for [event]'s reminder of [minutesBefore], considering occurrences starting at
     * or after [earliestOccurrenceStartUtcMillis]. Null when the series has no further occurrence
     * (the alarm should then be cancelled).
     */
    fun computeNextFire(
        expander: RecurrenceExpander,
        event: Event,
        minutesBefore: Int,
        earliestOccurrenceStartUtcMillis: Long,
    ): ScheduledFire? {
        val start = expander.nextOccurrenceStart(event, earliestOccurrenceStartUtcMillis) ?: return null
        return ScheduledFire(
            fireAtUtcMillis = start - minutesBefore * MS_PER_MINUTE,
            occurrenceStartUtcMillis = start,
        )
    }
}
