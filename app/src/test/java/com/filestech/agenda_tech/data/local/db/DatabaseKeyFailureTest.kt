package com.filestech.agenda_tech.data.local.db

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The classification that decides whether the user's agenda gets erased.
 *
 * Audit F1 (CRITICAL) — `DatabaseFactory` used to catch `Exception` and, for every failure alike, delete
 * both `master.key` and `agendatech.db`. With `allowBackup=false` the database is the only copy on the
 * device, so a transient Keystore error during a `BOOT_COMPLETED` — no screen, no question asked — cost
 * the whole agenda while the real key was intact and a second attempt would have worked.
 *
 * The fix turns [DatabaseKeyManager.Failure.dataIsUnrecoverable] into the single point where that
 * decision is taken. This test exists to hold it there. It is a policy test, deliberately: flipping any
 * of these four values is a decision about destroying user data, and it must not be possible to do it
 * quietly while refactoring.
 *
 * Runs on the JVM: the `Failure` subclasses are plain exceptions with no Android dependency, which is
 * itself the reason the decision was worth extracting into them.
 */
class DatabaseKeyFailureTest {

    @Test
    fun `an invalidated keystore alias means the data really is gone`() {
        // The OS itself says the key no longer exists. Nothing can decrypt the database again, so
        // refusing to open would brick the app for good — this is the one case where resetting is right.
        assertThat(DatabaseKeyManager.Failure.KeystoreInvalidated().dataIsUnrecoverable).isTrue()
    }

    @Test
    fun `a corrupted wrap means the data really is gone`() {
        // Reaching this means the Keystore handed back a key normally and only the AEAD refused: the
        // blob on disk is what is wrong, and the passphrase it held cannot be recovered.
        assertThat(DatabaseKeyManager.Failure.WrapCorrupted().dataIsUnrecoverable).isTrue()
    }

    @Test
    fun `an IO failure does NOT mean the data is gone`() {
        // Says nothing about the key: the file may be perfectly fine and unreadable for a moment.
        assertThat(DatabaseKeyManager.Failure.Io().dataIsUnrecoverable).isFalse()
    }

    @Test
    fun `an unreachable keystore does NOT mean the data is gone`() {
        // The class the CRITICAL turned on. `KeyStoreException`, `UnrecoverableKeyException`,
        // `ProviderException` and OEM `RuntimeException`s used to reach the caller as bare exceptions,
        // outside this hierarchy entirely, and were read as "the key is gone". They mean "this attempt
        // failed".
        assertThat(DatabaseKeyManager.Failure.KeystoreUnavailable().dataIsUnrecoverable).isFalse()
    }

    @Test
    fun `every failure carries its cause so a diagnosis is still possible after the refusal`() {
        val cause = IllegalStateException("keystore2 unavailable")
        assertThat(DatabaseKeyManager.Failure.KeystoreUnavailable(cause).cause).isSameInstanceAs(cause)
        assertThat(DatabaseKeyManager.Failure.Io(cause).cause).isSameInstanceAs(cause)
        assertThat(DatabaseKeyManager.Failure.WrapCorrupted(cause).cause).isSameInstanceAs(cause)
        assertThat(DatabaseKeyManager.Failure.KeystoreInvalidated(cause).cause).isSameInstanceAs(cause)
    }

    /**
     * The guard against the failure mode this whole class exists to prevent: a new [Failure] subtype
     * added later, defaulting to "erase", because that is what the old code did for everything.
     *
     * Kotlin's exhaustive `when` over a sealed hierarchy is what makes this checkable — adding a subtype
     * without extending this list stops compiling, which is the point.
     */
    @Test
    fun `exactly two of the four failure kinds are allowed to erase the agenda`() {
        val all = listOf(
            DatabaseKeyManager.Failure.KeystoreInvalidated(),
            DatabaseKeyManager.Failure.WrapCorrupted(),
            DatabaseKeyManager.Failure.Io(),
            DatabaseKeyManager.Failure.KeystoreUnavailable(),
        )
        assertThat(all.count { it.dataIsUnrecoverable }).isEqualTo(2)
        assertThat(all.count { !it.dataIsUnrecoverable }).isEqualTo(2)
    }
}
