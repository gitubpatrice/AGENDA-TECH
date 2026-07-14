package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.di.DefaultDispatcher
import com.filestech.agenda_tech.domain.recurrence.EventOccurrence
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.repository.EventRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

/**
 * The query that backs the calendar views: streams every concrete [EventOccurrence] overlapping
 * `[windowStartUtcMillis, windowEndUtcMillis)`, recurring events already expanded, sorted by start.
 * Only events of currently-visible calendars are included, so toggling a calendar off in the
 * calendar manager hides its events everywhere (all views + widget).
 *
 * Expansion is CPU work, so it runs on [DefaultDispatcher] (the repositories keep their DB reads on
 * IO upstream). Re-emits whenever the underlying events or calendar visibility change.
 */
class ObserveOccurrencesInRangeUseCase @Inject constructor(
    private val repository: EventRepository,
    private val calendarRepository: CalendarRepository,
    private val expander: RecurrenceExpander,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) {
    operator fun invoke(windowStartUtcMillis: Long, windowEndUtcMillis: Long): Flow<List<EventOccurrence>> {
        require(windowEndUtcMillis >= windowStartUtcMillis) {
            "window end ($windowEndUtcMillis) must be >= start ($windowStartUtcMillis)"
        }
        return combine(
            repository.observeForExpansion(windowStartUtcMillis, windowEndUtcMillis),
            calendarRepository.observeVisible(),
        ) { events, visibleCalendars ->
            val visibleIds = visibleCalendars.mapTo(HashSet()) { it.id }
            events
                .filter { it.calendarId in visibleIds }
                .flatMap { expander.expand(it, windowStartUtcMillis, windowEndUtcMillis) }
                .sortedBy { it.startUtcMillis }
        }.flowOn(defaultDispatcher)
    }
}
