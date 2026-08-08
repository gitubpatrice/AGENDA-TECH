package com.filestech.agenda_tech.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.filestech.agenda_tech.core.time.TimeZones
import timber.log.Timber
import java.time.ZoneId

/**
 * Forward Room migrations. They MUST NOT change the SQLCipher passphrase, so an `adb install -r`
 * upgrade never re-prompts setup or loses data.
 *
 * They must also be **additive** — adding columns and indexes, never rewriting existing rows — with
 * exactly one carved-out exception, [MIGRATION_5_6], which repairs a column this app had itself
 * written unusable values into. The exception is narrow on purpose: it touches one column, only in
 * rows where the stored value cannot be resolved at all, and it destroys nothing, because a value no
 * reader can resolve is a value no reader is using. Any future migration that wants to rewrite rows
 * has to justify itself the same way.
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

    /**
     * v6 (2026-08): repairs `events.time_zone` where an import stored a name nothing can resolve.
     *
     * ## What went wrong
     *
     * Audit F3 — the `.ics` importer stored the file's `TZID` string verbatim while computing the
     * instant with the *device* zone whenever it could not resolve that string. Every `.ics` Outlook
     * or Exchange produces names zones the Windows way (`Romance Standard Time`), so those rows kept a
     * name that no later reader resolved: the expander fell back to UTC, which drifts a recurring
     * occurrence by an hour across each DST boundary, and the exporter fell back to UTC too, moving
     * the event by a whole offset on every export/import round trip.
     *
     * The importer is fixed, so no new row can be written this way. This exists because fixing the
     * importer does nothing for the rows already stored — and those are the ones the user has.
     *
     * ## What it changes, and what it deliberately does not
     *
     * Only `time_zone`, and only where the stored value is **not already canonical** — that is, where
     * a later reader would not get back exactly what is stored by resolving it. That covers both a
     * value nothing resolves and one that resolves only after trimming or unquoting; both are values
     * every reader has to re-interpret, and the point of the repair is that none of them should have
     * to. (Stated this way after external review: an earlier wording said "only where it cannot be
     * resolved", which is not what [TimeZones.isCanonical] tests.)
     *
     * The instants are left exactly as they are: they were computed at import time and are the only
     * record of what the file said, so recomputing them here would be guessing twice.
     *
     * Cost, since this runs while the database is being opened and possibly inside a boot broadcast:
     * one pass for the `DISTINCT`, then one `UPDATE` per **distinct unusable value** — not per row.
     * A personal agenda holds a handful of zones however many events it has, and the realistic worst
     * case (a whole calendar imported from one Outlook export) is a single bad value, hence two passes
     * over a table measured in thousands of rows.
     *
     * A Windows name is repaired to the zone it denotes, which is the true fix. Anything else falls
     * back to the device's current zone — the same zone the importer used to compute the instant, so
     * the repaired label agrees with the instant that sits beside it. It is an approximation when the
     * device has since changed zone, and a strictly better one than the UTC every reader was using.
     *
     * All-day rows are untouched by construction: the importer already stored a resolvable zone for
     * them.
     *
     * The schema is unchanged, so Room's identity hash is unchanged and its validation is unaffected;
     * the version bump exists only to give this repair a place to run exactly once.
     */
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val fallback = ZoneId.systemDefault()
            val stored = mutableListOf<String>()
            db.query("SELECT DISTINCT time_zone FROM events").use { cursor ->
                while (cursor.moveToNext()) {
                    if (!cursor.isNull(0)) stored += cursor.getString(0)
                }
            }
            // Distinct values, not rows: a personal agenda holds a handful of zones however many
            // events it has, and this runs while the database is being opened.
            stored.filterNot(TimeZones::isCanonical).forEach { raw ->
                val repaired = TimeZones.normalize(raw, fallback)
                db.execSQL(
                    "UPDATE events SET time_zone = ? WHERE time_zone = ?",
                    arrayOf<Any>(repaired, raw),
                )
                // The raw value is a zone name from a calendar file, not agenda content — no title,
                // location or note. It is also the only way to tell a repaired Windows name from a
                // value that fell back.
                Timber.i("Migration 5→6: unresolvable time zone '%s' repaired to '%s'", raw, repaired)
            }
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
    )
}
