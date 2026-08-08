package com.filestech.agenda_tech.data.local.db

import android.content.Context
import androidx.room.Room
import com.filestech.agenda_tech.core.crypto.wipe
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the SQLCipher-backed [AppDatabase].
 *
 * The SQLCipher native library must be loaded once before the first connection is opened —
 * `System.loadLibrary("sqlcipher")` does that.
 *
 * ## Passphrase lifetime (audit SEC-1)
 *
 * SQLCipher keys **every** connection its pool opens, not just the first one: each
 * `SQLiteConnection` re-reads `SQLiteDatabaseConfiguration.password` when it opens. The passphrase
 * therefore has to stay resident for as long as the database is open — wiping it after the build
 * would break the next pool connection. What we can control is that *our* copy does not outlive the
 * build: SQLCipher gets a copy it owns, and the caller's array is wiped in a `finally`.
 */
@Singleton
class DatabaseFactory @Inject constructor(
    private val keyManager: DatabaseKeyManager,
) {

    fun build(context: Context): AppDatabase {
        loadNativeOnce()
        // SEC/ROB-1 — only the passphrase acquisition is guarded (never the Room build/migration).
        // If the Keystore key is gone/corrupted the encrypted DB is cryptographically unrecoverable
        // and there is no backup (allowBackup=false), so we reset to a fresh usable DB and flag it,
        // instead of crashing on every launch forever. A genuine migration bug still surfaces as a
        // visible crash later — it is never silently wiped here.
        val raw = try {
            keyManager.getOrCreatePassphrase()
        } catch (e: Exception) {
            Timber.e(e, "DB passphrase unrecoverable — resetting the local database")
            keyManager.destroyKeyFile()
            context.deleteDatabase(AppDatabase.DATABASE_NAME)
            markResetPending(context)
            keyManager.getOrCreatePassphrase() // fresh key; a second failure is a truly broken device
        }
        return try {
            rekeyLegacyZeroKeyDatabase(context, raw)
            open(context, raw)
        } finally {
            raw.wipe()
        }
    }

    // The only spread in the codebase, and it is Room's own vararg API (`addMigrations(vararg …)`):
    // the copy detekt warns about is one array of a handful of migrations, made once per process.
    @Suppress("SpreadOperator")
    private fun open(context: Context, raw: ByteArray): AppDatabase {
        // SQLCipher stores this array by reference and needs it for the lifetime of the pool, so it
        // gets its own copy rather than the caller's (which is wiped as soon as build() returns).
        val factory = SupportOpenHelperFactory(raw.copyOf())
        val db = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .openHelperFactory(factory)
            // Additive forward migrations only (cf. [Migrations]); the passphrase is unchanged
            // across bumps so `adb install -r` upgrades transparently. Downgrades are not
            // supported — we prefer a visible crash over a silent wipe of the user's agenda.
            .addMigrations(*Migrations.ALL)
            .fallbackToDestructiveMigrationOnDowngrade(false)
            .build()
        // SEC-1 — Room opens lazily on the first query, so nothing has run `PRAGMA key` yet at this
        // point. Touching the helper forces the real SQLCipher open here, inside build(), where a
        // keying failure is still attributable instead of surfacing later as an unrelated DAO crash.
        db.openHelper.writableDatabase
        return db
    }

    /**
     * Audit SEC-1 — repairs a database that earlier builds encrypted with 32 nul bytes.
     *
     * Until this was fixed the passphrase array was wiped right after `Room.build()`, i.e. *before*
     * Room's lazy first query ran `PRAGMA key`, so SQLCipher keyed the file with an all-zero
     * passphrase while the real Keystore-wrapped key went unused. Those files still exist on every
     * device that ran an affected build, and they cannot be opened with the real key.
     *
     * This is deliberately self-diagnosing: a database that already opens with the real key is left
     * untouched, so the repair is a no-op on fresh installs and on devices that were never affected.
     * A file that opens with neither key is left alone too — that is not this bug, and Room must be
     * allowed to surface it rather than have it silently papered over.
     */
    private fun rekeyLegacyZeroKeyDatabase(context: Context, raw: ByteArray) {
        val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        if (!dbFile.exists()) return
        if (opensWith(dbFile, raw)) {
            // Already keyed correctly. Sweep any .prerekey left by a run that died between a
            // successful rekey and its own cleanup — harmless, but it is a copy of the agenda.
            deleteStaleBackups(dbFile)
            return
        }

        val zeroKey = ByteArray(raw.size)
        if (!opensWith(dbFile, zeroKey)) {
            Timber.w("DB opens with neither the real nor the legacy zero key — leaving it to Room")
            return
        }

        Timber.w("Legacy zero-key database detected (SEC-1) — rekeying to the Keystore passphrase")
        // PRAGMA rekey rewrites every page in place. The agenda has no other copy on the device
        // (allowBackup=false), so a power loss mid-rewrite must not be able to destroy it.
        val backups = copyAside(dbFile)
        try {
            changePassword(dbFile, from = zeroKey, to = raw)
            check(opensWith(dbFile, raw)) { "rekey reported success but the DB still will not open" }
            backups.forEach { it.delete() }
        } catch (t: Throwable) {
            Timber.e(t, "Rekey failed — restoring the pre-rekey database")
            restore(backups, dbFile)
            throw t
        }
    }

    /**
     * True when [dbFile] can be opened *and read* with [key]. A wrong key fails on the first read.
     *
     * The connection is opened and closed here, so — unlike the pool copy handed to Room — this
     * copy of the key has no reason to outlive the probe and is wiped on the way out.
     */
    private fun opensWith(dbFile: File, key: ByteArray): Boolean {
        val probeKey = key.copyOf()
        return try {
            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                probeKey,
                null,
                SQLiteDatabase.OPEN_READONLY,
                null,
            )
            try {
                db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
                true
            } finally {
                db.close()
            }
        } catch (t: Throwable) {
            Timber.d("DB did not open with the candidate key: %s", t.message)
            false
        } finally {
            probeKey.wipe()
        }
    }

    private fun changePassword(dbFile: File, from: ByteArray, to: ByteArray) {
        val oldKey = from.copyOf()
        val newKey = to.copyOf()
        try {
            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                oldKey,
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null,
            )
            try {
                db.changePassword(newKey)
            } finally {
                db.close()
            }
        } finally {
            oldKey.wipe()
            newKey.wipe()
        }
    }

    /** Copies the DB and its sidecars aside, returning the copies made (empty when nothing existed). */
    private fun copyAside(dbFile: File): List<File> =
        SIDECAR_SUFFIXES.mapNotNull { suffix ->
            val source = File(dbFile.path + suffix)
            if (!source.exists()) return@mapNotNull null
            val copy = File(dbFile.path + suffix + BACKUP_SUFFIX)
            source.copyTo(copy, overwrite = true)
            copy
        }

    /**
     * Puts the pre-rekey copies back.
     *
     * `renameTo`, not `copyTo`: a copy truncates the destination before writing, so an interruption
     * leaves a half-written database *and* — if the copy is then dropped — nothing to retry from.
     * A rename on the same directory is atomic, so the file is either fully restored or untouched.
     * A backup that could not be moved into place is **kept**: it is the last copy of the agenda,
     * and the failure path is exactly where disk trouble is most likely.
     */
    private fun restore(backups: List<File>, dbFile: File) {
        backups.forEach { copy ->
            val target = File(copy.path.removeSuffix(BACKUP_SUFFIX))
            if (!target.delete() && target.exists()) {
                Timber.e("Could not clear %s — keeping the .prerekey copy", target.name)
                return@forEach
            }
            if (!copy.renameTo(target)) {
                Timber.e("Could not restore %s — the .prerekey copy is kept", target.name)
            }
        }
        // Sidecars that did not exist before the rekey must not survive it either: a stale -wal
        // against a restored -db reads as corruption.
        SIDECAR_SUFFIXES.drop(1).forEach { suffix ->
            val sidecar = File(dbFile.path + suffix)
            if (sidecar.exists() && backups.none { it.path == sidecar.path + BACKUP_SUFFIX }) {
                sidecar.delete()
            }
        }
    }

    /** Drops leftover `.prerekey` copies once the database is known to open with the real key. */
    private fun deleteStaleBackups(dbFile: File) {
        SIDECAR_SUFFIXES.forEach { suffix ->
            File(dbFile.path + suffix + BACKUP_SUFFIX).takeIf { it.exists() }?.delete()
        }
    }

    private fun markResetPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_RESET_PENDING, true).apply()
    }

    @Synchronized
    private fun loadNativeOnce() {
        if (loaded) return
        System.loadLibrary("sqlcipher")
        loaded = true
    }

    companion object {
        @Volatile private var loaded = false

        private const val PREFS = "agendatech_db"
        private const val KEY_RESET_PENDING = "db_reset_pending"

        /** The database file itself first, then the WAL sidecars Room may have left beside it. */
        private val SIDECAR_SUFFIXES = listOf("", "-wal", "-shm")
        private const val BACKUP_SUFFIX = ".prerekey"

        /**
         * Returns true once if the DB had to be reset after an unrecoverable key failure, clearing
         * the flag. The UI reads this at startup to inform the user their data was reset.
         */
        fun consumeResetFlag(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_RESET_PENDING, false)) return false
            prefs.edit().remove(KEY_RESET_PENDING).apply()
            return true
        }
    }
}
