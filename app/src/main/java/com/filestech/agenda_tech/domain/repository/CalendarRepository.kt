package com.filestech.agenda_tech.domain.repository

import com.filestech.agenda_tech.domain.model.Calendar
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for [Calendar]s. Implementations map domain models to/from Room entities
 * and run all work off the main thread. The domain layer never sees a Room type.
 */
interface CalendarRepository {

    /** Streams all calendars, ordered for display. Emits on every change. */
    fun observeAll(): Flow<List<Calendar>>

    /** Streams only the calendars currently toggled visible — the set the views should draw. */
    fun observeVisible(): Flow<List<Calendar>>

    suspend fun getById(id: Long): Calendar?

    /** The calendar previously imported from [sourceId] (e.g. `"device:6"`), or null (idempotent import). */
    suspend fun findBySourceId(sourceId: String): Calendar?

    /** Number of calendars — used to decide whether the first-run default calendar is needed. */
    suspend fun count(): Int

    /** Inserts or updates; returns the calendar's id (freshly generated on insert). */
    suspend fun upsert(calendar: Calendar): Long

    suspend fun setVisibility(id: Long, visible: Boolean)

    suspend fun delete(id: Long)
}
