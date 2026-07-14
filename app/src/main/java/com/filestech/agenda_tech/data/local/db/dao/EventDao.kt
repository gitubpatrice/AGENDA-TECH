package com.filestech.agenda_tech.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.filestech.agenda_tech.data.local.db.entity.EventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {

    /**
     * Events that **overlap** the half-open window `[startUtcMillis, endUtcMillis)` — i.e. that
     * start before the window ends and end after it begins. This is the correct predicate for
     * drawing a day/week/month view (a multi-day event straddling the edge still appears).
     *
     * Phase 2: recurring events (`rrule_freq` non-null) whose *base* occurrence sits outside the
     * window are not matched here — the `RecurrenceExpander` queries them separately and expands
     * their occurrences into the window.
     */
    @Query(
        """
        SELECT * FROM events
        WHERE start_utc_millis < :endUtcMillis AND end_utc_millis > :startUtcMillis
        ORDER BY start_utc_millis ASC
        """,
    )
    fun observeInRange(startUtcMillis: Long, endUtcMillis: Long): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE calendar_id = :calendarId ORDER BY start_utc_millis ASC")
    fun observeByCalendar(calendarId: Long): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getById(id: Long): EventEntity?

    @Upsert
    suspend fun upsert(entity: EventEntity): Long

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int
}
