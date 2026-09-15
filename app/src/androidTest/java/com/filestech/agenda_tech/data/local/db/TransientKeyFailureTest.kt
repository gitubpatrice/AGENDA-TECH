package com.filestech.agenda_tech.data.local.db

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.agenda_tech.core.crypto.AeadCipher
import com.filestech.agenda_tech.core.crypto.KeystoreManager
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Audit F1 (CRITICAL) — a **transient** key failure must not erase the agenda.
 *
 * ## The defect this locks down
 *
 * `DatabaseFactory.build` caught `Exception` and, for every failure alike, called `destroyKeyFile()` and
 * `deleteDatabase()`. `allowBackup=false` means the database is the user's only copy on the device, and
 * the worst path has no user in front of it: `BootReceiver` builds the database to reschedule alarms, so
 * a Keystore hiccup during the boot storm destroyed the whole agenda with no screen shown and no
 * question asked — while the real key was intact and a second attempt would have worked.
 *
 * ## How the failure is provoked, without a mock
 *
 * `master.key` is replaced by a **directory** of the same name. `keyFile.exists()` then still returns
 * true, so `DatabaseKeyManager` takes its `unwrap()` path, and `readBytes()` on a directory raises an
 * `IOException` → [DatabaseKeyManager.Failure.Io], which is transient by classification.
 *
 * No mocking framework, no `open` keyword added to production code: the failure is real, raised by the
 * filesystem, on the exact code path a real I/O error would take. And crucially the key bytes are kept
 * aside, so the test can then prove the data was **recoverable all along** — which is the whole claim of
 * the finding. A test that only checked "the file still exists" would not distinguish a preserved agenda
 * from an unreadable one.
 *
 * Run on the Galaxy S9 (API 29) by serial. Never via `connectedAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class TransientKeyFailureTest {

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
        keyFile().delete()
        keyFile().takeIf { it.isDirectory }?.deleteRecursively()
    }

    @After
    fun tearDown() {
        // Undo the sabotage first, or the directory outlives the test and breaks the next one.
        keyFile().takeIf { it.isDirectory }?.deleteRecursively()
        clearDatabaseFiles()
        keyFile().delete()
    }

    /**
     * The regression test for the CRITICAL. Under the old code this test fails on its very first
     * assertion after the failure: the database file is gone.
     */
    @Test
    fun aTransientKeyFailureLeavesTheDatabaseIntactAndRecoverable() {
        // 1. A real agenda, with a row in it.
        factory.build(context).apply {
            openHelper.writableDatabase.execSQL(INSERT_CALENDAR)
            close()
        }
        val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        assertThat(dbFile.exists()).isTrue()
        val sizeBefore = dbFile.length()

        // 2. Keep the real key aside, then make reading it fail transiently.
        val savedKeyBytes = keyFile().readBytes()
        assertThat(savedKeyBytes).isNotEmpty()
        assertThat(keyFile().delete()).isTrue()
        assertThat(keyFile().mkdirs()).isTrue()

        // 3. The build must REFUSE, and refuse with the transient classification.
        val failure = runCatching { factory.build(context) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(DatabaseKeyManager.Failure::class.java)
        assertThat((failure as DatabaseKeyManager.Failure).dataIsUnrecoverable).isFalse()

        // 4. Nothing was erased. This is the assertion the old code failed.
        assertThat(dbFile.exists()).isTrue()
        assertThat(dbFile.length()).isEqualTo(sizeBefore)

        // 5. And the data really was recoverable: put the key back, and the row is still there.
        keyFile().deleteRecursively()
        keyFile().writeBytes(savedKeyBytes)
        factory.build(context).apply {
            openHelper.readableDatabase.query("SELECT name FROM calendars").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("Consultation")
            }
            close()
        }
    }

    /**
     * The reset path still works — the fix must not brick a device whose key is genuinely gone.
     *
     * A byte of the GCM tag in `master.key` is flipped while the Keystore key stays intact: the AEAD
     * refuses the blob with `AEADBadTagException`, that is [DatabaseKeyManager.Failure.WrapCorrupted],
     * classified unrecoverable. The database must be reset and the flag raised so the user is told,
     * rather than the app failing to launch forever.
     *
     * This test used to delete the Keystore alias instead, relying on `getOrCreateKey` minting a new key
     * that the old blob then failed against. That regeneration was the defect fixed alongside this
     * rewrite (see [aMissingKeystoreKeyIsNeverReplacedWhileItsDatabaseExists]), so corruption is now
     * provoked where it really lives: in the bytes.
     */
    @Test
    fun aCorruptedWrapStillResetsTheDatabaseAndFlagsIt() {
        factory.build(context).apply {
            openHelper.writableDatabase.execSQL(INSERT_CALENDAR)
            close()
        }
        // Drain any flag left by an earlier run so the assertion below is about this one.
        DatabaseFactory.consumeResetFlag(context)

        // The last byte belongs to the 128-bit GCM tag (AeadCipher: version | iv(12) | ct | tag).
        val blob = keyFile().readBytes()
        blob[blob.lastIndex] = (blob[blob.lastIndex].toInt() xor 0x01).toByte()
        keyFile().writeBytes(blob)

        // Must not throw: a truly dead key is the one case where resetting is the right answer.
        factory.build(context).apply {
            openHelper.readableDatabase.query("SELECT count(*) FROM calendars").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getInt(0)).isEqualTo(0) // fresh database
            }
            close()
        }
        // The user has to be told their data was reset — silence here is what the flag exists to prevent.
        assertThat(DatabaseFactory.consumeResetFlag(context)).isTrue()
    }

    /**
     * A Keystore that returns no key for an existing database must never get a **new** key minted under
     * the same alias.
     *
     * On API 26–30 that null is also what an unreachable keystore daemon produces. The old code generated
     * a key there, the real one was destroyed, and the database was reset as unrecoverable. Deleting the
     * alias is the observable stand-in for the unreachable daemon: the test asserts nothing was erased
     * and **no key was created** — the assertion the old code fails, since `getOrCreateKey` recreated it.
     *
     * From API 31 a null is conclusive (`KEY_NOT_FOUND`), and the reset is the right answer. That branch
     * is written out here but **executed by no test run today**: CI and the S9 are API 29. The decision
     * between the two is covered for every API level by `KeystoreManagerMissingKeyTest` on the JVM.
     */
    @Test
    fun aMissingKeystoreKeyIsNeverReplacedWhileItsDatabaseExists() {
        factory.build(context).apply {
            openHelper.writableDatabase.execSQL(INSERT_CALENDAR)
            close()
        }
        DatabaseFactory.consumeResetFlag(context)
        val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        val sizeBefore = dbFile.length()

        KeystoreManager().deleteKey(KeystoreManager.ALIAS_DB_MASTER)

        val outcome = runCatching { factory.build(context) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            outcome.getOrThrow().close()
            assertThat(DatabaseFactory.consumeResetFlag(context)).isTrue()
        } else {
            val failure = outcome.exceptionOrNull()
            assertThat(failure).isInstanceOf(DatabaseKeyManager.Failure::class.java)
            assertThat((failure as DatabaseKeyManager.Failure).dataIsUnrecoverable).isFalse()
            assertThat(dbFile.exists()).isTrue()
            assertThat(dbFile.length()).isEqualTo(sizeBefore)
            assertThat(KeystoreManager().containsAlias(KeystoreManager.ALIAS_DB_MASTER)).isFalse()
            assertThat(DatabaseFactory.consumeResetFlag(context)).isFalse()
        }
    }

    private fun keyFile(): File = File(File(context.filesDir, "db"), "master.key")

    private fun clearDatabaseFiles() {
        val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        SIDECARS.forEach { suffix -> File(dbFile.path + suffix).delete() }
    }

    private companion object {
        const val INSERT_CALENDAR =
            "INSERT INTO calendars (name, color, visible, is_default, created_at) " +
                "VALUES ('Consultation', 0, 1, 1, 0)"

        val SIDECARS = listOf("", "-wal", "-shm", ".prerekey", "-wal.prerekey", "-shm.prerekey")
    }
}
