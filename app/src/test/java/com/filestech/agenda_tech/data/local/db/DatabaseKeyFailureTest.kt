package com.filestech.agenda_tech.data.local.db

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.AEADBadTagException

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
     * added later and defaulting to "erase", because that is what the old code did for everything.
     *
     * The guard is the **exhaustive `when`** in [expectedPolicyFor], not the list below. External review
     * caught that an earlier version of this test claimed a hard-coded `listOf(...)` would "stop
     * compiling" when a subtype was added — it would not, and the comment saying so was exactly the kind
     * of lying doc this audit found six times elsewhere in the repo. A `when` over a sealed hierarchy,
     * used as an expression, genuinely does fail to compile: adding a fifth [Failure] forces whoever adds
     * it to state, here, whether it may erase the user's agenda.
     */
    @Test
    fun `every failure kind states its policy, and only two of them may erase`() {
        val all = listOf(
            DatabaseKeyManager.Failure.KeystoreInvalidated(),
            DatabaseKeyManager.Failure.WrapCorrupted(),
            DatabaseKeyManager.Failure.Io(),
            DatabaseKeyManager.Failure.KeystoreUnavailable(),
        )
        all.forEach { failure ->
            assertThat(failure.dataIsUnrecoverable).isEqualTo(expectedPolicyFor(failure))
        }
        assertThat(all.count { it.dataIsUnrecoverable }).isEqualTo(2)
    }

    /**
     * The compile-time guard. Deliberately a `when` **without** an `else`: a new [Failure] subtype breaks
     * this build until its destroy-or-preserve policy is written down here.
     */
    private fun expectedPolicyFor(failure: DatabaseKeyManager.Failure): Boolean = when (failure) {
        is DatabaseKeyManager.Failure.KeystoreInvalidated -> true
        is DatabaseKeyManager.Failure.WrapCorrupted -> true
        is DatabaseKeyManager.Failure.Io -> false
        is DatabaseKeyManager.Failure.KeystoreUnavailable -> false
    }

    // --- The classification that closed the second half of F1 --------------------------------------
    //
    // Found by BOTH external reviewers, independently, on the first version of the F1 fix: obtaining a
    // usable `SecretKey` handle does not mean the Keystore can then run the GCM operation.
    // `Cipher.init` / `doFinal` on an AndroidKeyStore key can fail transiently, and `AeadCipher` wraps
    // every one of those into the same `AppError.Crypto`. Mapping them all to `WrapCorrupted` — which is
    // classified unrecoverable — meant a transient crypto error erased the agenda: the very defect F1 set
    // out to remove, one layer further down.
    //
    // These tests are the non-regression guard for that. They run on the JVM because
    // `classifyCryptoFailure` is pure — which is exactly why the decision was worth extracting from the
    // Keystore plumbing it used to be buried in.

    @Test
    fun `a failed GCM tag is real corruption — the passphrase is gone`() {
        val failure = DatabaseKeyManager.classifyCryptoFailure(AEADBadTagException("tag mismatch"))
        assertThat(failure).isInstanceOf(DatabaseKeyManager.Failure.WrapCorrupted::class.java)
        assertThat(failure.dataIsUnrecoverable).isTrue()
    }

    @Test
    fun `a malformed blob is real corruption`() {
        // What AeadCipher's own `require()` calls raise: unsupported version byte, blob too short.
        val failure =
            DatabaseKeyManager.classifyCryptoFailure(IllegalArgumentException("Unsupported AEAD version"))
        assertThat(failure).isInstanceOf(DatabaseKeyManager.Failure.WrapCorrupted::class.java)
        assertThat(failure.dataIsUnrecoverable).isTrue()
    }

    @Test
    fun `a provider failure during the cipher operation must NOT erase anything`() {
        // The case both reviewers found. keystore2 busy, StrongBox cold, an OEM provider refusing the
        // operation — none of it is evidence about the bytes on disk.
        val failure = DatabaseKeyManager.classifyCryptoFailure(ProviderException("keystore2 busy"))
        assertThat(failure).isInstanceOf(DatabaseKeyManager.Failure.KeystoreUnavailable::class.java)
        assertThat(failure.dataIsUnrecoverable).isFalse()
    }

    @Test
    fun `a keystore exception during the cipher operation must NOT erase anything`() {
        val failure = DatabaseKeyManager.classifyCryptoFailure(KeyStoreException("operation failed"))
        assertThat(failure).isInstanceOf(DatabaseKeyManager.Failure.KeystoreUnavailable::class.java)
        assertThat(failure.dataIsUnrecoverable).isFalse()
    }

    @Test
    fun `an unrecognised cause defaults to NOT erasing`() {
        // The rule the whole fix rests on: unknown means "this attempt failed", never "the key is gone".
        val unknown = DatabaseKeyManager.classifyCryptoFailure(IllegalStateException("who knows"))
        assertThat(unknown.dataIsUnrecoverable).isFalse()
        assertThat(DatabaseKeyManager.classifyCryptoFailure(null).dataIsUnrecoverable).isFalse()
    }

    @Test
    fun `the classified failure carries its cause, so the refusal can still be diagnosed`() {
        val cause = ProviderException("keystore2 busy")
        assertThat(DatabaseKeyManager.classifyCryptoFailure(cause).cause).isSameInstanceAs(cause)
    }
}
