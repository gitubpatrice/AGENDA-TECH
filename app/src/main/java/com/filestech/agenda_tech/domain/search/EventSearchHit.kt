package com.filestech.agenda_tech.domain.search

import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.Event

/**
 * One search result: the matching event, dated.
 *
 * A recurring event has no single date, so [occurrenceStartUtcMillis] is the one the user actually
 * cares about — the next occurrence from now, or the last one if the series is over. Showing the
 * master's base start instead would date a weekly meeting to the day it was first created, years ago.
 *
 * [calendar] travels with the hit because search deliberately spans hidden calendars too: a result
 * has to say where it lives, or the user is left wondering why it isn't in their views.
 */
data class EventSearchHit(
    val event: Event,
    val calendar: Calendar,
    val occurrenceStartUtcMillis: Long,
    /** True when [occurrenceStartUtcMillis] is still ahead — drives ordering and the section header. */
    val isUpcoming: Boolean,
)
