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

    /** Streams the stored events whose interval overlaps `[startUtcMillis, endUtcMillis)`. */
    fun observeInRange(startUtcMillis: Long, endUtcMillis: Long): Flow<List<Event>>

    /**
     * Streams the events that are candidates for occurrence expansion over the window — non-recurring
     * events that overlap it, plus recurring events whose series could intersect it (base before the
     * window end, rule not yet ended). Feed the result to
     * [com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander] to get concrete occurrences;
     * [com.filestech.agenda_tech.domain.usecase.ObserveOccurrencesInRangeUseCase] does exactly that.
     */
    fun observeForExpansion(windowStartUtcMillis: Long, windowEndUtcMillis: Long): Flow<List<Event>>

    /** Streams the events belonging to a given calendar. */
    fun observeByCalendar(calendarId: Long): Flow<List<Event>>

    suspend fun getById(id: Long): Event?

    /** All events — used to export the whole agenda to `.ics`. */
    suspend fun getAll(): List<Event>

    /** Inserts or updates; returns the event's id (freshly generated on insert). */
    suspend fun upsert(event: Event): Long

    suspend fun delete(id: Long)
}
