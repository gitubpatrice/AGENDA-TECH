package com.filestech.agenda_tech.domain.recurrence

import com.filestech.agenda_tech.domain.model.Event

/**
 * A single concrete instance of an [Event] on the timeline.
 *
 * For a non-recurring event there is exactly one occurrence whose start/end equal the event's.
 * For a recurring event the [RecurrenceExpander] emits one [EventOccurrence] per generated
 * instant: [startUtcMillis] / [endUtcMillis] are that occurrence's absolute instants (already
 * resolved through the event's time zone, so DST is baked in), while [event] still carries the
 * series' shared data (title, calendar, colour…).
 *
 * This is the type the calendar views render — never the raw stored [Event] for a recurring series.
 */
data class EventOccurrence(
    val event: Event,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
) {
    /** True when this instance came from expanding a recurrence rule (vs. a one-shot event). */
    val isRecurringInstance: Boolean get() = event.isRecurring
}
