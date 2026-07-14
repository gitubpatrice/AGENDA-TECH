package com.filestech.agenda_tech.domain.repository

import com.filestech.agenda_tech.domain.model.Event
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for [Event]s.
 *
 * IMPORTANT (phase 2): [observeInRange] returns the *stored* events whose base start falls in the
 * window — it does NOT yet expand recurring events into their concrete occurrences. Occurrence
 * expansion (respecting time zone / DST, `EXDATE`, and per-occurrence overrides) is the job of the
 * `RecurrenceExpander` layered on top of this repository, added when the calendar views land.
 */
interface EventRepository {

    /** Streams the events whose base start instant is within `[startUtcMillis, endUtcMillis)`. */
    fun observeInRange(startUtcMillis: Long, endUtcMillis: Long): Flow<List<Event>>

    /** Streams the events belonging to a given calendar. */
    fun observeByCalendar(calendarId: Long): Flow<List<Event>>

    suspend fun getById(id: Long): Event?

    /** Inserts or updates; returns the event's id (freshly generated on insert). */
    suspend fun upsert(event: Event): Long

    suspend fun delete(id: Long)
}
