package com.filestech.agenda_tech.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.filestech.agenda_tech.data.local.db.entity.CalendarEntity
import com.filestech.agenda_tech.data.local.db.entity.EventEntity
import com.filestech.agenda_tech.data.local.db.entity.ReminderEntity

/**
 * The restore path: wipe the agenda and rewrite it from a backup file.
 *
 * Grouped in its own DAO rather than scattered across the three per-entity DAOs — these operations
 * only make sense together, inside one transaction, and keeping them here stops a bulk `deleteAll`
 * from being reachable from ordinary code paths.
 *
 * Rows are inserted with the **ids recorded in the file**, which is only sound because the tables
 * were just emptied: it preserves `calendar_id`, `recurrence_parent_id` and `event_id` links for
 * free, with no id-remapping pass to get wrong.
 */
@Dao
interface BackupDao {

    // Deleted child-first and explicitly rather than leaning on ON DELETE CASCADE: the intent is
    // visible in the code, and it holds even if the foreign_keys pragma is ever off.
    @Query("DELETE FROM reminders")
    suspend fun deleteAllReminders()

    @Query("DELETE FROM events")
    suspend fun deleteAllEvents()

    @Query("DELETE FROM calendars")
    suspend fun deleteAllCalendars()

    @Insert
    suspend fun insertCalendars(rows: List<CalendarEntity>)

    @Insert
    suspend fun insertEvents(rows: List<EventEntity>)

    @Insert
    suspend fun insertReminders(rows: List<ReminderEntity>)
}
