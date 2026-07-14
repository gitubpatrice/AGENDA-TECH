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

    // @Upsert (not @Insert REPLACE): REPLACE would DELETE+INSERT the row and cascade-delete this
    // calendar's events on every edit. @Upsert does a true INSERT-or-UPDATE, preserving children.
    @Upsert
    suspend fun upsert(entity: CalendarEntity): Long

    @Query("UPDATE calendars SET visible = :visible WHERE id = :id")
    suspend fun setVisibility(id: Long, visible: Boolean)

    @Query("DELETE FROM calendars WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM calendars")
    suspend fun count(): Int
}
