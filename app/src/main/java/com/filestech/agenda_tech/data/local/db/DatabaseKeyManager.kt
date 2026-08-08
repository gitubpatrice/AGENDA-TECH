package com.filestech.agenda_tech.data.local.db

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import com.filestech.agenda_tech.core.crypto.AeadCipher
import com.filestech.agenda_tech.core.crypto.KeystoreManager
import com.filestech.agenda_tech.core.crypto.wipe
import com.filestech.agenda_tech.core.result.Outcome
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the SQLCipher passphrase: a random 32-byte key wrapped by an AndroidKeyStore AES-GCM key
 * (hardware-backed on devices with a TEE). This is the **real** at-rest protection — not a
 * placeholder — so the encrypted `agendatech.db` cannot be read off the device without the
 * Keystore-held key.
 *
 * File layout: `<files>/db/master.key` — version(1) || nonce(12) || ct+tag(N)
 *
 * On first run a 32-byte random key is generated, encrypted under [KeystoreManager.ALIAS_DB_MASTER],
 * and persisted. Subsequent runs decrypt it to recover the passphrase.
 *
 * Distinguishing genuine Keystore invalidation (lock-screen credential change, Knox OTA reset)
 * from a transient decrypt failure avoids silent data loss: the caller receives a typed [Failure]
 * and surfaces a recovery flow instead of auto-wiping the key.
 *
 * Security posture (documented in SECURITY.md): the DB key is NOT gated behind user authentication
 * (`setUserAuthenticationRequired = false`), matching the SMS Tech baseline — at-rest protection
 * relies on the device lock + the Keystore. Add an opt-in biometric gate on the vault in a later
 * phase if a stronger threat model is required.
 */
@Singleton
class DatabaseKeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystore: KeystoreManager,
    private val aead: AeadCipher,
) {
    private val secureRandom = SecureRandom()

    private val keyDir: File by lazy {
        File(context.filesDir, "db").apply { if (!exists()) mkdirs() }
    }
    private val keyFile: File by lazy { File(keyDir, "master.key") }

    sealed class Failure(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

        /**
         * Whether the SQLCipher passphrase is **gone for good**, making the encrypted database
         * cryptographically dead — the only state in which erasing it is the right answer.
         *
         * Audit F1 — this property exists because the distinction was being made here and thrown away
         * by the caller. `DatabaseFactory` caught `Exception`, deleted `master.key` **and**
         * `agendatech.db`, and did so for every failure alike. With `allowBackup=false` the database is
         * the user's only copy, so a Keystore hiccup during a `BOOT_COMPLETED` — no screen, no
         * question asked — cost the whole agenda while the real key was intact and a second attempt
         * would have worked.
         *
         * `false` is the safe answer and the default for anything unrecognised: a launch that refuses
         * to open is recoverable, an erased agenda is not.
         */
        abstract val dataIsUnrecoverable: Boolean

        /** The Keystore alias is gone or invalidated. The existing wrapped key cannot be recovered. */
        class KeystoreInvalidated(cause: Throwable? = null) :
            Failure("AndroidKeyStore alias was invalidated; existing data unrecoverable", cause) {
            // The OS itself says the key no longer exists. Nothing can decrypt the database again.
            override val dataIsUnrecoverable = true
        }

        /**
         * AEAD decryption failed **while the Keystore was healthy enough to hand back a key** — so the
         * blob on disk is what is wrong, not the attempt. The passphrase it held is unrecoverable.
         *
         * Distinct from [KeystoreUnavailable] on purpose: getting here means `getOrCreateKey` returned
         * normally and only `aead.decrypt` refused.
         */
        class WrapCorrupted(cause: Throwable? = null) :
            Failure("wrapped DB key is corrupted on disk", cause) {
            override val dataIsUnrecoverable = true
        }

        /**
         * I/O failure while reading/writing the key blob. Says nothing about the key itself: the file
         * may be perfectly fine and unreadable for a moment.
         */
        class Io(cause: Throwable? = null) : Failure("I/O failure reading the wrapped DB key", cause) {
            override val dataIsUnrecoverable = false
        }

        /**
         * The AndroidKeyStore could not be reached, or refused to hand back the key for a reason that
         * is not invalidation — `KeyStoreException`, `UnrecoverableKeyException`, `ProviderException`,
         * or the plain `RuntimeException`s some OEM implementations raise.
         *
         * This is the class the CRITICAL of audit F1 turned on: these used to reach `DatabaseFactory`
         * as bare exceptions, outside the [Failure] hierarchy entirely, and its `catch (e: Exception)`
         * read every one of them as "the key is gone". They mean **this attempt failed**.
         */
        class KeystoreUnavailable(cause: Throwable? = null) :
            Failure("AndroidKeyStore unreachable; this attempt failed, the key is not gone", cause) {
            override val dataIsUnrecoverable = false
        }
    }

    /** Returns the raw 32-byte SQLCipher key, generating it on first call. */
    @Throws(Failure::class)
    fun getOrCreatePassphrase(): ByteArray =
        if (keyFile.exists()) unwrap() else generateAndWrap()

    /** Forcibly destroys the wrapped key file (used by a future "reset all" / wipe flow). */
    fun destroyKeyFile() {
        if (keyFile.exists()) keyFile.delete()
    }

    /**
     * The AndroidKeyStore key that wraps the passphrase, with **every** way it can fail mapped onto the
     * [Failure] hierarchy.
     *
     * Audit F1 — this mapping is the fix. `getOrCreateKey` declares no checked exception and wraps
     * nothing, so a `KeyStoreException` from the keystore2 daemon, an `UnrecoverableKeyException`, a
     * `ProviderException`, or an OEM `RuntimeException` used to travel up as-is, past a hierarchy built
     * precisely to classify them, into a `catch (e: Exception)` that deleted the agenda. Anything not
     * recognised as genuine invalidation is now typed [Failure.KeystoreUnavailable], i.e. transient,
     * i.e. **not** a reason to erase anything.
     */
    private fun wrappingKey(): SecretKey = try {
        keystore.getOrCreateKey(KeystoreManager.ALIAS_DB_MASTER, allowUserIv = true)
    } catch (e: KeyPermanentlyInvalidatedException) {
        Timber.e("Keystore key invalidated (likely credential change on this device)")
        throw Failure.KeystoreInvalidated(e)
    } catch (e: UserNotAuthenticatedException) {
        // Transient, and reclassified from KeystoreInvalidated after two reviewers flagged the mapping.
        //
        // This key is created with `setUserAuthenticationRequired(false)`, so per Android's contract this
        // exception cannot be raised for it — both reviewers confirmed the path is unreachable. But its
        // meaning, when it IS raised, is literally "authenticate and retry": it never says the key is
        // gone. Classifying unreachable-but-transient as destructive is a bet with no upside — if the
        // path stays dead the choice never matters, and if some OEM firmware raises it anyway the old
        // mapping erased an agenda that was perfectly recoverable.
        throw Failure.KeystoreUnavailable(e)
    } catch (e: Throwable) {
        throw Failure.KeystoreUnavailable(e)
    }

    private fun generateAndWrap(): ByteArray {
        val raw = ByteArray(AeadCipher.KEY_BYTES).also(secureRandom::nextBytes)
        val secretKey = try {
            wrappingKey()
        } catch (e: Failure) {
            raw.wipe()
            throw e
        }
        val wrapped = when (val r = aead.encrypt(secretKey, raw)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> {
                raw.wipe()
                // A fresh random key wrapped under a fresh Keystore key cannot be "corrupt on disk":
                // nothing has been written yet. Classified by cause like every other AEAD failure, so a
                // transient provider error on first run is not recorded as data loss.
                throw classifyCryptoFailure(r.error.cause)
            }
        }
        // Audit SEC-2 — atomic write: stage to a temp file then rename, so a crash / power loss
        // mid-write can never leave a truncated master.key that would type as WrapCorrupted and
        // brick the (still-empty) database on first launch. rename() is atomic on the same volume.
        try {
            val tmp = File(keyDir, "master.key.tmp")
            tmp.outputStream().use { it.write(wrapped) }
            if (!tmp.renameTo(keyFile)) {
                tmp.delete()
                throw IOException("atomic rename of master.key failed")
            }
        } catch (e: Throwable) {
            raw.wipe()
            throw Failure.Io(e)
        }
        return raw
    }

    private fun unwrap(): ByteArray {
        val wrapped = try {
            keyFile.readBytes()
        } catch (e: Throwable) {
            throw Failure.Io(e)
        }
        val secretKey = try {
            wrappingKey()
        } catch (e: Failure) {
            wrapped.wipe()
            throw e
        }
        return when (val r = aead.decrypt(secretKey, wrapped)) {
            is Outcome.Success -> {
                wrapped.wipe()
                r.value
            }
            is Outcome.Failure -> {
                wrapped.wipe()
                // We DO NOT auto-delete the keyFile here: a silent wipe = silent data loss. The caller
                // decides, and it decides on the CLASS of failure — hence the classification by cause
                // rather than a blanket WrapCorrupted.
                throw classifyCryptoFailure(r.error.cause)
            }
        }
    }

    companion object {
        /**
         * Classifies an AEAD failure by its **cause**, instead of assuming a bad blob.
         *
         * Found independently by both external reviewers of the F1 fix, and it was the dangerous half of
         * it: `getOrCreateKey` returning a usable `SecretKey` handle does **not** mean the Keystore can
         * then run the GCM operation. `Cipher.init` / `doFinal` on an AndroidKeyStore key can fail
         * transiently — a `ProviderException`, a `KeyStoreException`, an OEM `RuntimeException`, keystore2
         * busy during a boot storm — and [AeadCipher] wraps every one of those into the same
         * `AppError.Crypto`. Mapping all of them to [Failure.WrapCorrupted], which is classified
         * unrecoverable, meant a **transient crypto error erased the agenda**: the very defect F1 set out
         * to remove, reintroduced one layer further down, and reached with no retry at all.
         *
         * Only causes that say "these bytes are not what we wrote" count as corruption. Everything else,
         * including anything unrecognised, is transient — the safe side.
         *
         * In the companion rather than on the instance because it reads no state: that makes it a pure
         * decision, testable on the JVM without a `Context`, in the same spirit as `ReminderScheduling`
         * being separated from `ReminderScheduler`.
         */
        internal fun classifyCryptoFailure(cause: Throwable?): Failure = when (cause) {
            // The GCM tag did not verify: the blob was tampered with or truncated, or it was wrapped
            // under a key that no longer exists (alias deleted, then silently regenerated by
            // getOrCreateKey). Both mean the passphrase it held is gone for good.
            is AEADBadTagException,
            is BadPaddingException,
            is IllegalBlockSizeException,
            // AeadCipher.decrypt's own header checks: unsupported version byte, blob too short.
            is IllegalArgumentException,
            -> Failure.WrapCorrupted(cause)

            // ProviderException, KeyStoreException, InvalidKeyException, an OEM RuntimeException, or no
            // cause at all: the operation could not be performed. That is not evidence about the bytes.
            else -> Failure.KeystoreUnavailable(cause)
        }
    }
}
