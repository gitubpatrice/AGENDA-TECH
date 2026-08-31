package com.filestech.agenda_tech.domain.birthday

import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.EventKind
import java.time.Instant
import java.time.ZoneId

/**
 * The age a birthday's occurrence marks, or null when there is none to show.
 *
 * A birthday carries no birth year of its own: the master event **starts on the birth date**, and
 * every occurrence the expander produces is one yearly step from it. The age is therefore the
 * difference between the two years, and nothing extra needs storing.
 *
 * Null in the three cases where a number would be a lie rather than a fact:
 *  - the event is not a birthday;
 *  - the event is a per-occurrence override. An override starts on the date of the occurrence it
 *    replaces, not on the birth date, so there is no birth year here to subtract — the difference
 *    would be zero and the age would silently read as "nothing" anyway. Made explicit rather than
 *    left to fall out of the arithmetic, because the two are only equal by accident: the day the
 *    editor stops rewriting an override's start, the accident stops holding. Reaching the master's
 *    start would mean threading it through five view models for one year of one birthday, which is
 *    not worth it; the occurrence keeps its cake elsewhere and simply shows no number that year.
 *  - the occurrence is the birth year itself or earlier (age 0 or negative) — which is also what a
 *    user who did not know the year gets, having typed the current one;
 *  - the two instants land in the same year.
 *
 * Lives here, in one place, because four screens and the widget need the same answer and a copy in
 * each would drift.
 */
object BirthdayAge {

    fun of(event: Event, occurrenceStartUtcMillis: Long, zone: ZoneId): Int? {
        if (event.kind != EventKind.BIRTHDAY) return null
        if (event.isOverride) return null
        val birthYear = Instant.ofEpochMilli(event.startUtcMillis).atZone(zone).year
        val occurrenceYear = Instant.ofEpochMilli(occurrenceStartUtcMillis).atZone(zone).year
        return (occurrenceYear - birthYear).takeIf { it > 0 }
    }
}
