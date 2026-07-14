package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.repository.EventRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Streams the events whose base start falls within a half-open window `[startUtcMillis, endUtcMillis)`
 * — the query that backs the day / week / month views.
 *
 * Phase 2: this will compose with the `RecurrenceExpander` so recurring events yield one row per
 * concrete occurrence in the window. For now it forwards the stored events unchanged.
 */
class ObserveEventsInRangeUseCase @Inject constructor(
    private val repository: EventRepository,
) {
    operator fun invoke(startUtcMillis: Long, endUtcMillis: Long): Flow<List<Event>> {
        require(endUtcMillis >= startUtcMillis) {
            "range end ($endUtcMillis) must be >= start ($startUtcMillis)"
        }
        return repository.observeInRange(startUtcMillis, endUtcMillis)
    }
}
