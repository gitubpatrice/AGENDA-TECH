package com.filestech.agenda_tech.data.local.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.filestech.agenda_tech.data.local.db.entity.EventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {

    /**
     * Candidate events for occurrence expansion over `[windowStartUtcMillis, windowEndUtcMillis)`:
     *  - non-recurring events that overlap the window, and
     *  - recurring events whose series *could* intersect the window — the base starts before the
     *    window ends and the rule has not already ended (`rrule_until` null or ≥ window start).
     *
     * The [com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander] then turns each
     * recurring candidate into its concrete in-window occurrences — hence this intentionally returns
     * recurring events whose *base* sits before the window.
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

    /** ROB-NEW-2 — delete a recurring master and its overrides in one transaction (all-or-nothing). */
    @Transaction
    suspend fun deleteSeriesAtomic(parentId: Long) {
        deleteOverridesForParent(parentId)
        delete(parentId)
    }

    /** ROB-NEW-2 — upsert one event and delete another atomically (exclude-from-master + drop override). */
    @Transaction
    suspend fun upsertAndDelete(entity: EventEntity, deleteId: Long) {
        upsert(entity)
        delete(deleteId)
    }

    /**
     * Writes a per-occurrence override and its master's updated rule in one transaction, returning
     * the override's rowid.
     *
     * The two belong together: an override exists *because* the master no longer produces that
     * instant. Written separately, an interruption between them would leave the master still
     * generating the occurrence its override already replaced — and the master's EXDATEs are not
     * internal bookkeeping, they are exported verbatim to `.ics` and read by the reminder scheduler.
     *
     * [master] is null when the exclusion is already recorded (nothing to rewrite).
     */
    @Transaction
    suspend fun upsertOverrideAndMaster(override: EventEntity, master: EventEntity?): Long {
        val id = upsert(override)
        master?.let { upsert(it) }
        return id
    }

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getById(id: Long): EventEntity?

    /** Every event — used to export the whole agenda to `.ics`. */
    @Query("SELECT * FROM events ORDER BY start_utc_millis ASC")
    suspend fun getAll(): List<EventEntity>

    /** Streams every event — the corpus search folds and filters in memory. */
    @Query("SELECT * FROM events ORDER BY start_utc_millis ASC")
    fun observeAll(): Flow<List<EventEntity>>

    /**
     * ⚠️ The returned `Long` is the rowid only on the INSERT path; on UPDATE Room returns `-1`.
     *
     * Do not use this value directly — go through `EventRepository.upsert`, which resolves it to the
     * event's real id. This comment used to ask every caller to remember the quirk; one forgot, and
     * saving an edited event with a reminder crashed on `Reminder(eventId = -1)` (FOREIGN KEY
     * constraint failed) in every release up to v0.4.0.
     */
    @Upsert
    suspend fun upsert(entity: EventEntity): Long

    /** Atomic batch insert (device/ICS import): all rows land in one transaction or none do. */
    @Transaction
    suspend fun upsertAll(entities: List<EventEntity>) {
        entities.forEach { upsert(it) }
    }

    /** (source_uid → id) of the imported events of a calendar, to update them in place on re-import. */
    @Query("SELECT id, source_uid FROM events WHERE calendar_id = :calendarId AND source_uid IS NOT NULL")
    suspend fun sourceUidRows(calendarId: Long): List<SourceUidRow>

    /** Original `created_at` of the given events — so a re-import update preserves it instead of resetting. */
    @Query("SELECT id, created_at FROM events WHERE id IN (:ids)")
    suspend fun createdAtByIds(ids: List<Long>): List<CreatedAtRow>

    /**
     * Place details of the given events. Address and GPS are enrichments the user typed here — no
     * calendar source carries them — so a re-import must read them back and keep them rather than
     * blanking them with the source's (always empty) values.
     */
    @Query("SELECT id, address, postal_code, city, gps_coordinates FROM events WHERE id IN (:ids)")
    suspend fun placeDetailsByIds(ids: List<Long>): List<PlaceDetailsRow>

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int
}

/** Projection for [EventDao.sourceUidRows]: the id of an imported event and its external source uid. */
data class SourceUidRow(
    val id: Long,
    @ColumnInfo(name = "source_uid") val sourceUid: String,
)

/** Projection for [EventDao.createdAtByIds]: an event id and its original creation timestamp. */
data class CreatedAtRow(
    val id: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Projection for [EventDao.placeDetailsByIds]: the locally-entered place details of an event. */
data class PlaceDetailsRow(
    val id: Long,
    @ColumnInfo(name = "address") val address: String?,
    @ColumnInfo(name = "postal_code") val postalCode: String?,
    @ColumnInfo(name = "city") val city: String?,
    @ColumnInfo(name = "gps_coordinates") val gpsCoordinates: String?,
)
