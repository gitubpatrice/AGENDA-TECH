package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import javax.inject.Inject

/**
 * Guarantees at least one calendar exists so events always have a home. Idempotent: on first run
 * (no calendars) it creates a single visible default one named [defaultName]; on every later run it
 * is a no-op. Invoked once at app start.
 */
class EnsureDefaultCalendarUseCase @Inject constructor(
    private val repository: CalendarRepository,
) {
    suspend operator fun invoke(defaultName: String) {
        if (repository.count() > 0) return
        repository.upsert(
            Calendar(
                name = defaultName,
                color = CalendarColor.DEFAULT,
                isVisible = true,
                isDefault = true,
            ),
        )
    }
}
