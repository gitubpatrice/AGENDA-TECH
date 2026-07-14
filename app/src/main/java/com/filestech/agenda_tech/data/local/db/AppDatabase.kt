package com.filestech.agenda_tech.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.filestech.agenda_tech.data.local.db.dao.CalendarDao
import com.filestech.agenda_tech.data.local.db.dao.EventDao
import com.filestech.agenda_tech.data.local.db.dao.ReminderDao
import com.filestech.agenda_tech.data.local.db.entity.CalendarEntity
import com.filestech.agenda_tech.data.local.db.entity.EventEntity
import com.filestech.agenda_tech.data.local.db.entity.ReminderEntity

/**
 * The SQLCipher-encrypted Room database. Built by [DatabaseFactory] with the passphrase held by
 * [DatabaseKeyManager]; forward migrations (once they exist) live in [Migrations] and must be
 * additive so `adb install -r` upgrades transparently.
 */
@Database(
    version = AppDatabase.SCHEMA_VERSION,
    exportSchema = true,
    entities = [
        CalendarEntity::class,
        EventEntity::class,
        ReminderEntity::class,
    ],
)
@TypeConverters(AgendaEnumConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun calendarDao(): CalendarDao
    abstract fun eventDao(): EventDao
    abstract fun reminderDao(): ReminderDao

    companion object {
        const val DATABASE_NAME = "agendatech.db"
        // v1 (initial schema): calendars, events (with structured rrule_* columns), reminders.
        // v2 (per-occurrence overrides): events.recurrence_parent_id + events.original_start.
        const val SCHEMA_VERSION = 3
    }
}
