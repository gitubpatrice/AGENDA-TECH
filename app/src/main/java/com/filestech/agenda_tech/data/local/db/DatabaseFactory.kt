package com.filestech.agenda_tech.data.local.db

import android.content.Context
import androidx.room.Room
import com.filestech.agenda_tech.core.crypto.wipe
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
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
        val raw = keyManager.getOrCreatePassphrase()
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

    @Synchronized
    private fun loadNativeOnce() {
        if (loaded) return
        System.loadLibrary("sqlcipher")
        loaded = true
    }

    companion object {
        @Volatile private var loaded = false
    }
}
