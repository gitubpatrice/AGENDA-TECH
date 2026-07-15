package com.filestech.agenda_tech.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Forward, additive Room migrations. MUST be additive (never rewrite existing rows) and MUST NOT
 * change the SQLCipher passphrase, so an `adb install -r` upgrade never re-prompts setup or loses
 * data.
 */
object Migrations {

    /**
     * v2 (2026-07): per-occurrence overrides. Adds `events.recurrence_parent_id` +
     * `events.original_start` (both nullable, null for masters/standalone) and the matching index
     * Room expects. Purely additive.
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE events ADD COLUMN recurrence_parent_id INTEGER")
            db.execSQL("ALTER TABLE events ADD COLUMN original_start INTEGER")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_events_recurrence_parent_id " +
                    "ON events (recurrence_parent_id)",
            )
        }
    }

    /**
     * v3 (2026-07): idempotent device-calendar import. Adds `calendars.source_id` +
     * `events.source_uid` (both nullable, null for user-created rows) and the lookup index Room
     * expects on `(calendar_id, source_uid)`. Purely additive.
     */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE calendars ADD COLUMN source_id TEXT")
            db.execSQL("ALTER TABLE events ADD COLUMN source_uid TEXT")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_events_calendar_id_source_uid " +
                    "ON events (calendar_id, source_uid)",
            )
        }
    }

    /**
     * v4 (2026-07): index `calendars.source_id`, looked up on every (re-)import to reuse a source's
     * calendar. Mirrors the index convention already applied to `events.source_uid`. Purely additive.
     */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_calendars_source_id ON calendars (source_id)",
            )
        }
    }

    /**
     * v5 (2026-07): postal address + GPS coordinates on an event. Four nullable text columns, no
     * index (never queried on). Purely additive: existing events keep their `location` untouched.
     */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE events ADD COLUMN address TEXT")
            db.execSQL("ALTER TABLE events ADD COLUMN postal_code TEXT")
            db.execSQL("ALTER TABLE events ADD COLUMN city TEXT")
            db.execSQL("ALTER TABLE events ADD COLUMN gps_coordinates TEXT")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
}
