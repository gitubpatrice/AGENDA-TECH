package com.filestech.agenda_tech.domain.reminder

import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.recurrence.ExpansionBudget
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
     *
     * [extraExcludedStartsUtcMillis] carries the instants replaced by per-occurrence overrides, read
     * from the live override rows — the same way the calendar views and search do it. This used to be
     * omitted, which left the scheduler as the only reader with its own answer to "does this occurrence
     * still exist": it trusted the `EXDATE`s persisted on the master alone. `ab05feb` removed that
     * asymmetry from search and never touched here. The editor writes both halves in one transaction
     * (`upsertOverrideAtomic`), so the two answers agree in normal use; they diverge on a hand-edited or
     * corrupted `.atbak`, which `RestoreBackupUseCase.validate` does not require to carry the master's
     * `EXDATE`. Two mechanisms for one question is the defect, whatever the odds of them disagreeing.
     *
     * [budget] bounds the whole pass. Without it, rescheduling N reminders pays
     * [RecurrenceExpander.MAX_SCAN_ITERATIONS] N times over — and N is exactly what an import controls.
     */
    fun computeNextFire(
        expander: RecurrenceExpander,
        event: Event,
        minutesBefore: Int,
        earliestOccurrenceStartUtcMillis: Long,
        extraExcludedStartsUtcMillis: Set<Long> = emptySet(),
        budget: ExpansionBudget? = null,
    ): ScheduledFire? {
        val start = expander.nextOccurrenceStart(
            event,
            earliestOccurrenceStartUtcMillis,
            extraExcludedStartsUtcMillis,
            budget,
        ) ?: return null
        return ScheduledFire(
            fireAtUtcMillis = start - minutesBefore * MS_PER_MINUTE,
            occurrenceStartUtcMillis = start,
        )
    }
}
