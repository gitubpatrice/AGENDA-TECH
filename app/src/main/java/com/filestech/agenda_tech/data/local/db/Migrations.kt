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

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
