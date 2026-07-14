package com.filestech.agenda_tech.domain.repository

import com.filestech.agenda_tech.domain.model.Reminder
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for [Reminder]s. Scheduling the actual OS alarm is a separate phase-2
 * concern — this contract only stores the user's intent (which reminders exist per event).
 */
interface ReminderRepository {

    /** Streams the reminders attached to a given event, soonest-first. */
    fun observeForEvent(eventId: Long): Flow<List<Reminder>>

    suspend fun upsert(reminder: Reminder): Long

    /** Removes every reminder attached to an event (used when the event is deleted). */
    suspend fun deleteForEvent(eventId: Long)

    suspend fun delete(id: Long)
}
