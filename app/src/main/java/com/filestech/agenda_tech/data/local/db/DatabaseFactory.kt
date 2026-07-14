package com.filestech.agenda_tech.data.local.db

import android.content.Context
import androidx.room.Room
import com.filestech.agenda_tech.core.crypto.wipe
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the SQLCipher-backed [AppDatabase]. The raw passphrase is wiped from JVM memory
 * immediately after Room consumes the factory.
 *
 * The SQLCipher native library must be loaded once before the first connection is opened —
 * `System.loadLibrary("sqlcipher")` does that.
 */
@Singleton
class DatabaseFactory @Inject constructor(
    private val keyManager: DatabaseKeyManager,
) {

    fun build(context: Context): AppDatabase {
        loadNativeOnce()
        // SEC/ROB-1 — only the passphrase acquisition is guarded (never the Room build/migration,
        // which opens lazily on first query). If the Keystore key is gone/corrupted the encrypted DB
        // is cryptographically unrecoverable and there is no backup (allowBackup=false), so we reset
        // to a fresh usable DB and flag it, instead of crashing on every launch forever. A genuine
        // migration bug still surfaces as a visible crash later — it is never silently wiped here.
        val raw = try {
            keyManager.getOrCreatePassphrase()
        } catch (e: Exception) {
            Timber.e(e, "DB passphrase unrecoverable — resetting the local database")
            keyManager.destroyKeyFile()
            context.deleteDatabase(AppDatabase.DATABASE_NAME)
            markResetPending(context)
            keyManager.getOrCreatePassphrase() // fresh key; a second failure is a truly broken device
        }
        val factory = SupportOpenHelperFactory(raw)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .openHelperFactory(factory)
            // Additive forward migrations only (cf. [Migrations]); the passphrase is unchanged
            // across bumps so `adb install -r` upgrades transparently. Downgrades are not
            // supported — we prefer a visible crash over a silent wipe of the user's agenda.
            .addMigrations(*Migrations.ALL)
            .fallbackToDestructiveMigrationOnDowngrade(false)
            .build()
        raw.wipe()
        return db
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
