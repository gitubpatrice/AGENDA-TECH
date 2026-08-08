package com.filestech.agenda_tech.data.local.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.agenda_tech.core.crypto.AeadCipher
import com.filestech.agenda_tech.core.crypto.KeystoreManager
import com.google.common.truth.Truth.assertThat
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The at-rest encryption of the agenda, exercised against a **real** SQLCipher and a **real**
 * AndroidKeyStore.
 *
 * ## Why this test exists
 *
 * Until now `app/src/androidTest/` held exactly one file — the Hilt runner — and no test at all. That
 * mattered because the worst defect in this project's history lived in code that cannot be exercised
 * on the JVM:
 *
 * > **v0.5.2 (SEC-1, CRITICAL)** — the passphrase array was wiped right after `Room.build()`, i.e.
 * > *before* Room's lazy first query ran `PRAGMA key`. The database was therefore encrypted with 32
 * > nul bytes from the scaffold commit onward, and the Keystore-wrapped key went unused. It was found
 * > by hand, on an emulator, once.
 *
 * A regression on it would keep all 241 JVM tests green. [realKeyIsActuallyUsed] is the assertion that
 * would have failed on the affected builds, phrased the way the defect presented itself: *the file must
 * NOT open with an all-zero key.*
 *
 * Run on the Galaxy S9 (API 29) by serial. Never via `connectedAndroidTest`, which targets every
 * attached device and clears app data — one of the attached devices is a real phone.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionTest {

    private lateinit var context: Context
    private lateinit var factory: DatabaseFactory
    private lateinit var keyManager: DatabaseKeyManager

    @Before
    fun setUp() {
        SqlCipherNative.load()
        context = ApplicationProvider.getApplicationContext()
        // Constructed directly rather than through Hilt: this is about the factory and the key, not
        // about the graph that hands them around.
        keyManager = DatabaseKeyManager(context, KeystoreManager(), AeadCipher())
        factory = DatabaseFactory(keyManager)
        clearDatabaseFiles()
    }

    @After
    fun tearDown() {
        clearDatabaseFiles()
    }

    /**
     * The regression test for v0.5.2's CRITICAL: the file must be keyed with the real Keystore-wrapped
     * passphrase, and must therefore be **unopenable** with the all-zero key the affected builds used.
     *
     * Both halves are asserted. Proving the real key opens it is not enough on its own — an all-zero
     * key also "opens" a file it encrypted itself, which is exactly why the defect survived a scaffold
     * and four releases unnoticed.
     */
    @Test
    fun realKeyIsActuallyUsed() {
        val db = factory.build(context)
        db.openHelper.writableDatabase.execSQL(INSERT_CALENDAR)
        db.close()

        val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        assertThat(dbFile.exists()).isTrue()

        val realKey = keyManager.getOrCreatePassphrase()
        assertThat(opensAndReads(dbFile, realKey)).isTrue()
        assertThat(opensAndReads(dbFile, ByteArray(realKey.size))).isFalse()
    }

    /** A rebuild must find the same rows: the passphrase has to be stable across process lifetimes. */
    @Test
    fun dataSurvivesAClosedAndRebuiltDatabase() {
        factory.build(context).apply {
            openHelper.writableDatabase.execSQL(INSERT_CALENDAR)
            close()
        }

        factory.build(context).apply {
            openHelper.readableDatabase.query("SELECT name FROM calendars").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("Consultation")
            }
            close()
        }
    }

    /**
     * `rekeyLegacyZeroKeyDatabase` repairs a file left behind by an affected build **without losing its
     * rows** — the whole point of the repair, and the half that a "does it open now?" check would miss.
     *
     * The stand-in file carries a table Room knows nothing about, on purpose: Room sees `user_version`
     * 0, creates its own schema alongside it, and the surviving `legacy_probe` row is then proof that
     * the rekey rewrote the pages rather than replacing the file.
     */
    @Test
    fun aLegacyZeroKeyDatabaseIsRekeyedAndKeepsItsRows() {
        val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()

        val realKey = keyManager.getOrCreatePassphrase()
        val zeroKey = ByteArray(realKey.size)
        SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, zeroKey, null, null).use { legacy ->
            legacy.execSQL("CREATE TABLE legacy_probe (id INTEGER PRIMARY KEY, v TEXT)")
            legacy.execSQL("INSERT INTO legacy_probe (v) VALUES ('agenda historique')")
        }
        assertThat(opensAndReads(dbFile, zeroKey)).isTrue()

        factory.build(context).apply {
            openHelper.readableDatabase.query("SELECT v FROM legacy_probe").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("agenda historique")
            }
            close()
        }

        // Rekeyed to the real passphrase, and the zero key no longer opens it.
        assertThat(opensAndReads(dbFile, realKey)).isTrue()
        assertThat(opensAndReads(dbFile, zeroKey)).isFalse()
        // The pre-rekey safety copies are swept once the file is known to open with the real key.
        assertThat(File(dbFile.path + ".prerekey").exists()).isFalse()
    }

    private fun opensAndReads(dbFile: File, key: ByteArray): Boolean = try {
        SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            key.copyOf(),
            null,
            SQLiteDatabase.OPEN_READONLY,
            null,
        ).use { db ->
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
            true
        }
    } catch (t: Throwable) {
        // A wrong key fails on the first read, which is the whole signal this helper reports.
        false
    }

    private fun clearDatabaseFiles() {
        val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        SIDECARS.forEach { suffix -> File(dbFile.path + suffix).delete() }
    }

    private companion object {
        /** Column list matches schema v1..v5: every NOT NULL column of `calendars` is supplied. */
        const val INSERT_CALENDAR =
            "INSERT INTO calendars (name, color, visible, is_default, created_at) " +
                "VALUES ('Consultation', 0, 1, 1, 0)"

        val SIDECARS = listOf("", "-wal", "-shm", ".prerekey", "-wal.prerekey", "-shm.prerekey")
    }
}
