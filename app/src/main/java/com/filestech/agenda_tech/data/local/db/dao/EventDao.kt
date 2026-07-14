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

    /**
     * Candidate events for occurrence expansion over `[windowStartUtcMillis, windowEndUtcMillis)`:
     *  - non-recurring events that overlap the window, and
     *  - recurring events whose series *could* intersect the window — the base starts before the
     *    window ends and the rule has not already ended (`rrule_until` null or ≥ window start).
     *
     * The [com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander] then turns each
     * recurring candidate into its concrete in-window occurrences. Unlike [observeInRange], this
     * intentionally returns recurring events whose *base* sits before the window.
     */
    @Query(
        """
        SELECT * FROM events
        WHERE (rrule_freq IS NULL
                   AND start_utc_millis < :windowEndUtcMillis
                   AND end_utc_millis > :windowStartUtcMillis)
           OR (rrule_freq IS NOT NULL
                   AND start_utc_millis < :windowEndUtcMillis
                   AND (rrule_until IS NULL OR rrule_until >= :windowStartUtcMillis))
        ORDER BY start_utc_millis ASC
        """,
    )
    fun observeForExpansion(windowStartUtcMillis: Long, windowEndUtcMillis: Long): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE calendar_id = :calendarId ORDER BY start_utc_millis ASC")
    fun observeByCalendar(calendarId: Long): Flow<List<EventEntity>>

    /** Every per-occurrence override (recurrence_parent_id set) — used to expand recurring series. */
    @Query("SELECT * FROM events WHERE recurrence_parent_id IS NOT NULL")
    fun observeOverrides(): Flow<List<EventEntity>>

    /** Deletes the overrides of a recurring master (called when the whole series is deleted). */
    @Query("DELETE FROM events WHERE recurrence_parent_id = :parentId")
    suspend fun deleteOverridesForParent(parentId: Long)

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getById(id: Long): EventEntity?

    /** Every event — used to export the whole agenda to `.ics`. */
    @Query("SELECT * FROM events ORDER BY start_utc_millis ASC")
    suspend fun getAll(): List<EventEntity>

    /**
     * ⚠️ Audit SEC-1 — the returned `Long` is the rowid only on the INSERT path; on UPDATE Room may
     * return `-1`. NEVER use it to link a child (e.g. a reminder's `event_id`) after an edit; use
     * the caller's known `entity.id` instead.
     */
    @Upsert
    suspend fun upsert(entity: EventEntity): Long

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int
}
