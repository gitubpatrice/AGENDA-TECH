package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.core.result.AppError
import com.filestech.agenda_tech.core.result.Outcome
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.repository.EventRepository
import javax.inject.Inject

/**
 * Validates then persists an [Event]. Trims the title and rejects blank ones, returning a typed
 * [AppError.Validation] the editor surfaces inline.
 *
 * The start/end ordering is not re-checked here: [Event] enforces `end >= start` at construction,
 * so an invalid interval can never reach this use case (the editor validates dates before building
 * the [Event]).
 */
class UpsertEventUseCase @Inject constructor(
    private val repository: EventRepository,
) {
    suspend operator fun invoke(event: Event): Outcome<Long> {
        val title = event.title.trim()
        if (title.isEmpty()) {
            return Outcome.Failure(AppError.Validation("blank title"))
        }
        return Outcome.Success(repository.upsert(event.copy(title = title)))
    }
}
