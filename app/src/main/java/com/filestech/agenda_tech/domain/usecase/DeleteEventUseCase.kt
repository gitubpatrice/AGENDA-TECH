package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.domain.repository.EventRepository
import javax.inject.Inject

/**
 * Deletes an event by id. Its reminders are removed automatically by the `ON DELETE CASCADE`
 * foreign key, so no orphan reminders/alarms are left behind.
 */
class DeleteEventUseCase @Inject constructor(
    private val repository: EventRepository,
) {
    suspend operator fun invoke(eventId: Long) = repository.delete(eventId)
}
