package com.filestech.agenda_tech.domain.reminder

import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.recurrence.ALL_DAY_PLACEMENT_BOUND_MILLIS
import com.filestech.agenda_tech.domain.recurrence.ExpansionBudget
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander
import com.filestech.agenda_tech.domain.recurrence.shownStartUtcMillis
import java.time.ZoneId

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
 *
 * "Start" here is where the occurrence begins on the phone's calendar ([shownStartUtcMillis]), and every
 * threshold below is on that calendar too. For a timed event it is the occurrence's instant. For an
 * all-day event it is midnight of its date on the phone, which is not its stored instant once the phone
 * has left the zone the event was created in: a reminder "at the start" of a holiday created in Paris
 * rang at 18:00 the day before in New York. A threshold built from a fired occurrence is placed on the
 * calendar the same way, by the caller ([com.filestech.agenda_tech.system.alarm.ReminderScheduler]).
 */
object ReminderScheduling {

    private const val MS_PER_MINUTE = 60_000L

    /** Earliest occurrence start to consider for the *initial* schedule, so the alarm is not in the past. */
    fun initialEarliestStart(nowUtcMillis: Long, minutesBefore: Int): Long =
        nowUtcMillis + minutesBefore * MS_PER_MINUTE

    /**
     * Earliest occurrence start to consider when rescheduling after a fire — strictly after it, and
     * never in the past.
     *
     * ## Le plancher manquait (audit AG-17)
     *
     * Cette fonction rendait `fired + 1` et rien d'autre, alors que son jumeau
     * [initialEarliestStart], quatre lignes plus haut, plafonne sur « maintenant » depuis toujours.
     * L'asymétrie mordait quand une alarme était délivrée très en retard — veille prolongée, mise en
     * sommeil agressive d'un constructeur : la reprogrammation visait l'occurrence suivante, déjà
     * passée. `setExactAndAllowWhileIdle` sur un instant passé tire IMMÉDIATEMENT, ce qui
     * reprogrammait l'occurrence d'après, qui tirait immédiatement, et ainsi de suite — une rafale de
     * notifications qui s'empilent (leur identifiant diffère par occurrence, donc elles ne se
     * remplacent pas). La séquence terminait, bornée par le nombre d'occurrences manquées ; ce n'est
     * pas pour autant ce qu'on attend d'un rappel.
     *
     * Le comportement voulu est de **sauter** au prochain tir futur : une occurrence manquée est
     * manquée, la rejouer trois jours plus tard n'informe de rien.
     *
     * [nowUtcMillis] et [minutesBefore] servent à replacer le plancher là où [initialEarliestStart]
     * le met — sur le *début d'occurrence* dont le rappel tomberait maintenant — et non sur l'instant
     * courant, sans quoi on sauterait une occurrence dont le rappel est encore à venir.
     */
    fun nextEarliestStart(
        firedOccurrenceStartUtcMillis: Long,
        nowUtcMillis: Long,
        minutesBefore: Int,
    ): Long = maxOf(
        firedOccurrenceStartUtcMillis + 1,
        initialEarliestStart(nowUtcMillis, minutesBefore),
    )

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
        zone: ZoneId = ZoneId.systemDefault(),
    ): ScheduledFire? {
        // An all-day occurrence's place on the calendar can be up to the bound before or after its stored
        // start, so the walk starts that much earlier. Places follow starts in order — one date per
        // occurrence — so the first occurrence placed at or after the threshold is the next one.
        val searchFrom = if (event.allDay) {
            earliestOccurrenceStartUtcMillis - ALL_DAY_PLACEMENT_BOUND_MILLIS
        } else {
            earliestOccurrenceStartUtcMillis
        }
        val start = expander.firstOccurrenceStart(event, searchFrom, extraExcludedStartsUtcMillis, budget) {
            event.shownStartUtcMillis(it, zone) >= earliestOccurrenceStartUtcMillis
        } ?: return null
        return ScheduledFire(
            fireAtUtcMillis = event.shownStartUtcMillis(start, zone) - minutesBefore * MS_PER_MINUTE,
            occurrenceStartUtcMillis = start,
        )
    }
}
