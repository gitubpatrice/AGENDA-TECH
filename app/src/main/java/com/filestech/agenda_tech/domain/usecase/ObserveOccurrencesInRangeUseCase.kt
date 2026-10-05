package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.core.di.DefaultDispatcher
import com.filestech.agenda_tech.domain.recurrence.ALL_DAY_PLACEMENT_BOUND_MILLIS
import com.filestech.agenda_tech.domain.recurrence.EventOccurrence
import com.filestech.agenda_tech.domain.recurrence.ExpansionBudget
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander
import com.filestech.agenda_tech.domain.recurrence.shownEndUtcMillis
import com.filestech.agenda_tech.domain.recurrence.shownStartUtcMillis
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.repository.EventRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import java.time.ZoneId
import javax.inject.Inject

/**
 * The query that backs the calendar views: streams every concrete [EventOccurrence] whose place on the
 * phone's calendar overlaps `[windowStartUtcMillis, windowEndUtcMillis)`, sorted by that place's start.
 *
 *  - The place is what [shownStartUtcMillis] / [shownEndUtcMillis] give: the instants of a timed
 *    occurrence, the phone's midnights of the dates of an all-day one. The two differ once the phone has
 *    left the zone the all-day event was created in, by up to [ALL_DAY_PLACEMENT_BOUND_MILLIS], so the
 *    events are read over a window that much wider and filtered on their place afterwards. Filtering on
 *    the stored instants showed such an event on two days and kept it from the window of its own date.
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
    /** [zone] is the phone's: the window's bounds are its midnights, and all-day occurrences are placed on them. */
    operator fun invoke(
        windowStartUtcMillis: Long,
        windowEndUtcMillis: Long,
        zone: ZoneId,
    ): Flow<List<EventOccurrence>> {
        require(windowEndUtcMillis >= windowStartUtcMillis) {
            "window end ($windowEndUtcMillis) must be >= start ($windowStartUtcMillis)"
        }
        val readStart = windowStartUtcMillis - ALL_DAY_PLACEMENT_BOUND_MILLIS
        val readEnd = windowEndUtcMillis + ALL_DAY_PLACEMENT_BOUND_MILLIS
        return combine(
            repository.observeForExpansion(readStart, readEnd),
            calendarRepository.observeVisible(),
            repository.observeOverrides(),
        ) { events, visibleCalendars, overrides ->
            // Audit F8 — one allowance for the whole pass. Without it the expander's per-event cap is
            // paid once per event, and the number of events is exactly what an import controls.
            val budget = ExpansionBudget()
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
                    expander.expand(event, readStart, readEnd, extraExcluded, budget)
                }
                .filter { it.shownStartUtcMillis(zone) < windowEndUtcMillis && it.shownEndUtcMillis(zone) > windowStartUtcMillis }
                .sortedBy { it.shownStartUtcMillis(zone) }
        }.flowOn(defaultDispatcher)
    }
}
