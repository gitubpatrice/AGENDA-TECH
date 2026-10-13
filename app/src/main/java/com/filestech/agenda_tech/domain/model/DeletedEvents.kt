package com.filestech.agenda_tech.domain.model

/**
 * What one deletion took away, kept for the few seconds its "Undo" is offered.
 *
 * It is undone by putting back what was removed and lifting what was added — never by writing a series
 * back the way it was. Between the deletion and the undo the user may have changed that series, and an
 * old copy written over it would silently revert the change.
 */
data class DeletedEvents(
    /** Rows removed outright — one event, or a series' master and its overrides — as they were. */
    val events: List<Event> = emptyList(),
    /** The reminders those rows carried, in minutes before, by event id. */
    val reminderMinutesByEventId: Map<Long, List<Int>> = emptyMap(),
    /**
     * The occurrence the deletion excluded from a series — set only when the deletion is what added that
     * exclusion. Deleting a moved occurrence finds its original date already excluded (the move did it):
     * lifting it on undo would bring back the series' own copy beside the restored one.
     */
    val addedExclusion: SeriesExclusion? = null,
) {
    val isEmpty: Boolean get() = events.isEmpty() && addedExclusion == null
}

/** One occurrence of the series [masterId], the one starting at [occurrenceStartUtcMillis], excluded from it. */
data class SeriesExclusion(val masterId: Long, val occurrenceStartUtcMillis: Long)
