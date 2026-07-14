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
 * `[windowStartUtcMillis, windowEndUtcMillis)`, sorted by start.
 *
 *  - Only events of currently-visible calendars are included (toggling a calendar off hides it
 *    everywhere, incl. the widget).
 *  - Recurring masters are expanded; the occurrence replaced by a per-occurrence override is
 *    skipped (via the override's `originalStartUtcMillis`), and the override event shows itself
 *    (it is a standalone non-recurring event).
 *
 * Expansion is CPU work, so it runs on [DefaultDispatcher]. Re-emits on any change to events,
 * overrides or calendar visibility.
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
            repository.observeOverrides(),
        ) { events, visibleCalendars, overrides ->
            val visibleIds = visibleCalendars.mapTo(HashSet()) { it.id }
            val excludedByParent = overrides
                .groupBy { it.recurrenceParentId }
                .mapValues { (_, list) -> list.mapNotNull { it.originalStartUtcMillis }.toHashSet() }

            events
                .filter { it.calendarId in visibleIds }
                .flatMap { event ->
                    val extraExcluded = if (event.isRecurring) {
                        excludedByParent[event.id].orEmpty()
                    } else {
                        emptySet()
                    }
                    expander.expand(event, windowStartUtcMillis, windowEndUtcMillis, extraExcluded)
                }
                .sortedBy { it.startUtcMillis }
        }.flowOn(defaultDispatcher)
    }
}
