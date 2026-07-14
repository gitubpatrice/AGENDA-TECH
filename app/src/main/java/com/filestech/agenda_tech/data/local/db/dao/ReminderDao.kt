package com.filestech.agenda_tech.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.filestech.agenda_tech.data.local.db.entity.ReminderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {

    @Query("SELECT * FROM reminders WHERE event_id = :eventId ORDER BY minutes_before ASC")
    fun observeForEvent(eventId: Long): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE event_id = :eventId ORDER BY minutes_before ASC")
    suspend fun getForEvent(eventId: Long): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getById(id: Long): ReminderEntity?

    /** Every reminder in the database — used to reschedule all alarms after a reboot. */
    @Query("SELECT * FROM reminders")
    suspend fun getAll(): List<ReminderEntity>

    /**
     * ⚠️ Audit SEC-1 — the returned `Long` is the rowid only on the INSERT path; on UPDATE Room may
     * return `-1`. Don't rely on it as a stable identifier after an edit; use the caller's known
     * `entity.id`.
     */
    @Upsert
    suspend fun upsert(entity: ReminderEntity): Long

    @Query("DELETE FROM reminders WHERE event_id = :eventId")
    suspend fun deleteForEvent(eventId: Long)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)
}
