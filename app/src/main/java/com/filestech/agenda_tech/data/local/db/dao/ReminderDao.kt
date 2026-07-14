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

    @Upsert
    suspend fun upsert(entity: ReminderEntity): Long

    @Query("DELETE FROM reminders WHERE event_id = :eventId")
    suspend fun deleteForEvent(eventId: Long)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)
}
