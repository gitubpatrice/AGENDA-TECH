package com.filestech.agenda_tech.data.local.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.agenda_tech.core.crypto.AeadCipher
import com.filestech.agenda_tech.core.crypto.KeystoreManager
import com.filestech.agenda_tech.core.time.TimeZones
import com.google.common.truth.Truth.assertThat
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId

/**
 * The five Room migrations (v1 → v6), driven through the **real** production path.
 *
 * ## Why this test exists
 *
 * `d015632` recorded "pas de test MigrationTestHelper (aucune migration n'en a)" as a known,
 * non-blocking gap. That was accurate when there were no migrations. There are now five, and each runs
 * on a file holding the user's only copy of their agenda (`allowBackup=false`). A migration that
 * dropped a column, or that Room judged inconsistent with the entity definitions, would surface as a
 * crash on the *user's* device after an `adb install -r` — never here, because nothing ran them.
 *
 * ## Why not `MigrationTestHelper`
 *
 * Room 2.8.4's helper parses the exported schema JSON with kotlinx-serialization and needs core
 * >= 1.8.0; this app pins 1.7.3. Measured on the S9: `AbstractMethodError` before a single migration
 * ran, and AGP's consistent resolution makes a test-only bump impossible. Bumping the shipped
 * serialization runtime — the library that writes the `.atbak` backup — to satisfy a test tool was the
 * wrong trade.
 *
 * What replaces it is stronger, not weaker. A v1 database is built by hand from the DDL Room itself
 * exported to `app/schemas/…/1.json`, then handed to [DatabaseFactory], which applies
 * [Migrations.ALL] against real SQLCipher with the real Keystore-wrapped key. **Room validates the
 * resulting schema itself**: if a migration and the entities disagree, opening throws
 * `IllegalStateException("Migration didn't properly handle…")`. So schema validation is still asserted
 * — by the same code that will run on the user's phone, rather than by a helper using a fake key on a
 * database the app never opens.
 *
 * Run on the Galaxy S9 (API 29) by serial. Never via `connectedAndroidTest`, which targets every
 * attached device and clears app data.
 */
@RunWith(AndroidJUnit4::class)
class MigrationsTest {

    private lateinit var context: Context
    private lateinit var factory: DatabaseFactory
    private lateinit var keyManager: DatabaseKeyManager

    @Before
    fun setUp() {
        SqlCipherNative.load()
        context = ApplicationProvider.getApplicationContext()
        keyManager = DatabaseKeyManager(context, KeystoreManager(), AeadCipher())
        factory = DatabaseFactory(keyManager)
        clearDatabaseFiles()
    }

    @After
    fun tearDown() {
        clearDatabaseFiles()
    }

    /**
     * A row written at v1 must still be there at v6, with the columns each migration added present and
     * null. That is what "additive" means, and it is the half a schema check cannot see: a migration
     * that recreated a table would validate perfectly and lose the agenda.
     *
     * Room's own schema validation runs as part of this test: `factory.build(context)` forces the open
     * (`db.openHelper.writableDatabase`), which is where Room compares the migrated schema against the
     * entities and refuses a mismatch.
     */
    @Test
    fun aRowWrittenAtV1SurvivesEveryMigrationAndRoomValidatesTheResult() {
        seedVersion1Database()

        factory.build(context).apply {
            openHelper.readableDatabase.query(
                "SELECT title, time_zone, start_utc_millis FROM events WHERE id = 1",
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("Consultation")
                assertThat(cursor.getString(1)).isEqualTo("Europe/Paris")
                assertThat(cursor.getLong(2)).isEqualTo(SEED_START_MILLIS)
            }

            // v2 — per-occurrence overrides.
            assertColumnsAreNull("recurrence_parent_id", "original_start")
            // v3 — idempotent device import.
            assertColumnsAreNull("source_uid")
            // v5 — place details.
            assertColumnsAreNull("address", "postal_code", "city", "gps_coordinates")

            // The calendar the event hangs off survived too — the foreign key is ON DELETE CASCADE, so
            // losing the calendar would have taken the event with it and this assertion is what tells
            // the two failures apart.
            openHelper.readableDatabase.query("SELECT name, is_default FROM calendars WHERE id = 1")
                .use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getString(0)).isEqualTo("Perso")
                    assertThat(cursor.getInt(1)).isEqualTo(1)
                }
            close()
        }
    }

    /**
     * v4 adds an index and nothing else, so it has no column to assert on. Its effect is checked where
     * it actually lives: in `sqlite_master`.
     */
    @Test
    fun theIndexesEachMigrationAddsExistAfterUpgrading() {
        seedVersion1Database()

        factory.build(context).apply {
            val indexes = mutableSetOf<String>()
            openHelper.readableDatabase
                .query("SELECT name FROM sqlite_master WHERE type = 'index'")
                .use { cursor ->
                    while (cursor.moveToNext()) indexes += cursor.getString(0)
                }
            // v2, v3 and v4 respectively.
            assertThat(indexes).contains("index_events_recurrence_parent_id")
            assertThat(indexes).contains("index_events_calendar_id_source_uid")
            assertThat(indexes).contains("index_calendars_source_id")
            close()
        }
    }

    /**
     * v6 repairs `events.time_zone` where the `.ics` importer stored a name no reader could resolve.
     *
     * This is the one migration on this branch that rewrites existing rows, so it is also the one that
     * has to prove it rewrites *only* what it claims: the canonical zone is left alone, and no
     * instant moves. A repair that shifted the stored instants would look identical in the schema and
     * would silently move every appointment in the agenda.
     */
    @Test
    fun theV6MigrationRepairsUnresolvableTimeZonesAndTouchesNothingElse() {
        seedVersion1Database()

        factory.build(context).apply {
            val zones = mutableMapOf<Long, String>()
            val starts = mutableMapOf<Long, Long>()
            openHelper.readableDatabase
                .query("SELECT id, time_zone, start_utc_millis FROM events ORDER BY id")
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        zones[cursor.getLong(0)] = cursor.getString(1)
                        starts[cursor.getLong(0)] = cursor.getLong(2)
                    }
                }

            // Already canonical — the migration must not touch it.
            assertThat(zones[1]).isEqualTo("Europe/Paris")
            // A Windows zone name is repaired to the zone it denotes. Tokyo rather than a European one
            // on purpose: it cannot be confused with the device's own zone, so the assertion still
            // means something when this runs on a phone set to Paris.
            assertThat(zones[2]).isEqualTo("Asia/Tokyo")
            // Nothing resolves this one, so it falls back to the device zone — the same zone the
            // importer used to compute the instant sitting next to it.
            assertThat(zones[3]).isEqualTo(ZoneId.systemDefault().id)
            assertThat(zones[4]).isEqualTo("Europe/Paris")

            // Every value is now one the app can resolve — the whole point of the repair.
            zones.values.forEach { assertThat(TimeZones.isCanonical(it)).isTrue() }

            // And not one instant moved.
            assertThat(starts.values.toSet()).containsExactly(SEED_START_MILLIS)
            close()
        }
    }

    private fun AppDatabase.assertColumnsAreNull(vararg columns: String) {
        openHelper.readableDatabase
            .query("SELECT ${columns.joinToString(", ")} FROM events WHERE id = 1")
            .use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                columns.indices.forEach { index ->
                    assertThat(cursor.isNull(index)).isTrue()
                }
            }
    }

    /**
     * Builds a v1 database with the DDL Room exported for v1, then stamps `user_version = 1` so Room
     * runs the migration chain rather than treating the file as new.
     *
     * The DDL is copied from `app/schemas/…/1.json` verbatim — if it ever drifts from what v1 really
     * was, Room's validation at the end of the migration chain is what says so.
     */
    private fun seedVersion1Database() {
        val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        val key = keyManager.getOrCreatePassphrase()
        SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, key, null, null).use { v1 ->
            V1_DDL.forEach(v1::execSQL)
            v1.execSQL(
                "INSERT INTO calendars (id, name, color, visible, is_default, created_at) " +
                    "VALUES (1, 'Perso', 0, 1, 1, 0)",
            )
            SEED_EVENTS.forEach { (id, zone) ->
                v1.execSQL(
                    "INSERT INTO events (id, calendar_id, title, start_utc_millis, end_utc_millis, " +
                        "time_zone, all_day, rrule_interval, rrule_by_weekdays, rrule_exdates, " +
                        "created_at, updated_at) VALUES ($id, 1, 'Consultation', $SEED_START_MILLIS, " +
                        "$SEED_END_MILLIS, '$zone', 0, 1, '', '', 0, 0)",
                )
            }
            v1.execSQL("PRAGMA user_version = 1")
        }
    }

    private fun clearDatabaseFiles() {
        val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        SIDECARS.forEach { suffix -> File(dbFile.path + suffix).delete() }
    }

    private companion object {
        const val SEED_START_MILLIS = 1767225600000L
        const val SEED_END_MILLIS = 1767229200000L

        /**
         * Row id → the `time_zone` a v1 database holds. Row 1 is already canonical; the other three are
         * the values the `.ics` importer really wrote before audit F3 — a Windows zone name from an
         * Outlook export, and a name nothing at all can resolve.
         */
        val SEED_EVENTS = listOf(
            1L to "Europe/Paris",
            2L to "Tokyo Standard Time",
            3L to "Not A Zone At All",
            4L to "Romance Standard Time",
        )

        val SIDECARS = listOf("", "-wal", "-shm", ".prerekey", "-wal.prerekey", "-shm.prerekey")

        /** Verbatim from `app/schemas/com.filestech.agenda_tech.data.local.db.AppDatabase/1.json`. */
        val V1_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS `calendars` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `color` INTEGER NOT NULL, `visible` INTEGER NOT NULL, " +
                "`is_default` INTEGER NOT NULL, `created_at` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`calendar_id` INTEGER NOT NULL, `title` TEXT NOT NULL, `description` TEXT, " +
                "`location` TEXT, `start_utc_millis` INTEGER NOT NULL, `end_utc_millis` INTEGER NOT NULL, " +
                "`time_zone` TEXT NOT NULL, `all_day` INTEGER NOT NULL, `rrule_freq` INTEGER, " +
                "`rrule_interval` INTEGER NOT NULL, `rrule_by_weekdays` TEXT NOT NULL, " +
                "`rrule_count` INTEGER, `rrule_until` INTEGER, `rrule_exdates` TEXT NOT NULL, " +
                "`color_override` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                "FOREIGN KEY(`calendar_id`) REFERENCES `calendars`(`id`) ON UPDATE NO ACTION " +
                "ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_events_calendar_id` ON `events` (`calendar_id`)",
            "CREATE INDEX IF NOT EXISTS `index_events_start_utc_millis` ON `events` (`start_utc_millis`)",
            "CREATE INDEX IF NOT EXISTS `index_events_end_utc_millis` ON `events` (`end_utc_millis`)",
            "CREATE TABLE IF NOT EXISTS `reminders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`event_id` INTEGER NOT NULL, `minutes_before` INTEGER NOT NULL, " +
                "FOREIGN KEY(`event_id`) REFERENCES `events`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_reminders_event_id` ON `reminders` (`event_id`)",
        )
    }
}
