package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.core.result.AppError
import com.filestech.agenda_tech.core.result.Outcome
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import javax.inject.Inject

/**
 * Validates then persists a [Calendar]. Trims the name and rejects blank ones before touching
 * the repository, returning a typed [AppError.Validation] the UI can surface directly.
 */
class UpsertCalendarUseCase @Inject constructor(
    private val repository: CalendarRepository,
) {
    suspend operator fun invoke(calendar: Calendar): Outcome<Long> {
        val name = calendar.name.trim()
        if (name.isEmpty()) {
            // Dev-facing AppError message (English, like the rest); the UI blocks blank names anyway.
            return Outcome.Failure(AppError.Validation("Calendar name must not be blank"))
        }
        val id = repository.upsert(calendar.copy(name = name))
        return Outcome.Success(id)
    }
}
