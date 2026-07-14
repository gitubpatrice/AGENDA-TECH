package com.filestech.agenda_tech.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.filestech.agenda_tech.data.local.db.entity.CalendarEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarDao {

    @Query("SELECT * FROM calendars ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<CalendarEntity>>

    @Query("SELECT * FROM calendars WHERE visible = 1 ORDER BY name COLLATE NOCASE ASC")
    fun observeVisible(): Flow<List<CalendarEntity>>

    @Query("SELECT * FROM calendars WHERE id = :id")
    suspend fun getById(id: Long): CalendarEntity?

    /** The calendar previously imported from a given external source, if any (idempotent import). */
    @Query("SELECT * FROM calendars WHERE source_id = :sourceId LIMIT 1")
    suspend fun findBySourceId(sourceId: String): CalendarEntity?

    /**
     * `@Upsert` (not `@Insert(onConflict = REPLACE)`): REPLACE would DELETE+INSERT the row and
     * cascade-delete this calendar's events on every edit. `@Upsert` does a true INSERT-or-UPDATE,
     * preserving children.
     *
     * ⚠️ Audit SEC-1 — the returned `Long` is the rowid only on the INSERT path; on UPDATE Room may
     * return `-1`. NEVER use it to link a child (e.g. an event's `calendar_id`); use the caller's
     * known `entity.id` instead.
     */
    @Upsert
    suspend fun upsert(entity: CalendarEntity): Long

    @Query("UPDATE calendars SET visible = :visible WHERE id = :id")
    suspend fun setVisibility(id: Long, visible: Boolean)

    @Query("DELETE FROM calendars WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM calendars")
    suspend fun count(): Int
}
