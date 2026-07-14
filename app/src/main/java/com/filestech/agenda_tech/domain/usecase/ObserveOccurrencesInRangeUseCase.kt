package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.di.DefaultDispatcher
import com.filestech.agenda_tech.domain.recurrence.EventOccurrence
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander
import com.filestech.agenda_tech.domain.repository.EventRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The query that backs the calendar views: streams every concrete [EventOccurrence] overlapping
 * `[windowStartUtcMillis, windowEndUtcMillis)`, recurring events already expanded, sorted by start.
 *
 * Expansion is CPU work, so it runs on [DefaultDispatcher] (the repository keeps its own DB reads
 * on IO upstream). Re-emits whenever the underlying events change.
 */
class ObserveOccurrencesInRangeUseCase @Inject constructor(
    private val repository: EventRepository,
    private val expander: RecurrenceExpander,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) {
    operator fun invoke(windowStartUtcMillis: Long, windowEndUtcMillis: Long): Flow<List<EventOccurrence>> {
        require(windowEndUtcMillis >= windowStartUtcMillis) {
            "window end ($windowEndUtcMillis) must be >= start ($windowStartUtcMillis)"
        }
        return repository.observeForExpansion(windowStartUtcMillis, windowEndUtcMillis)
            .map { events ->
                events
                    .flatMap { expander.expand(it, windowStartUtcMillis, windowEndUtcMillis) }
                    .sortedBy { it.startUtcMillis }
            }
            .flowOn(defaultDispatcher)
    }
}
