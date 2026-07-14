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
import java.security.SecureRandom
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
        /** The Keystore alias is gone or invalidated. The existing wrapped key cannot be recovered. */
        class KeystoreInvalidated(cause: Throwable? = null) :
            Failure("AndroidKeyStore alias was invalidated; existing data unrecoverable", cause)

        /** AEAD decryption failed but the Keystore is healthy — likely file corruption. */
        class WrapCorrupted(cause: Throwable? = null) :
            Failure("wrapped DB key is corrupted on disk", cause)

        /** I/O failure while reading/writing the key blob. */
        class Io(cause: Throwable? = null) : Failure("I/O failure reading the wrapped DB key", cause)
    }

    /** Returns the raw 32-byte SQLCipher key, generating it on first call. */
    @Throws(Failure::class)
    fun getOrCreatePassphrase(): ByteArray =
        if (keyFile.exists()) unwrap() else generateAndWrap()

    /** Forcibly destroys the wrapped key file (used by a future "reset all" / wipe flow). */
    fun destroyKeyFile() {
        if (keyFile.exists()) keyFile.delete()
    }

    private fun generateAndWrap(): ByteArray {
        val raw = ByteArray(AeadCipher.KEY_BYTES).also(secureRandom::nextBytes)
        val secretKey = try {
            keystore.getOrCreateKey(KeystoreManager.ALIAS_DB_MASTER, allowUserIv = true)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw Failure.KeystoreInvalidated(e)
        }
        val wrapped = when (val r = aead.encrypt(secretKey, raw)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> {
                raw.wipe()
                throw Failure.WrapCorrupted()
            }
        }
        try {
            keyFile.outputStream().use { it.write(wrapped) }
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
            keystore.getOrCreateKey(KeystoreManager.ALIAS_DB_MASTER, allowUserIv = true)
        } catch (e: KeyPermanentlyInvalidatedException) {
            Timber.e("Keystore key invalidated (likely credential change on this device)")
            throw Failure.KeystoreInvalidated(e)
        } catch (e: UserNotAuthenticatedException) {
            // We do not require user auth on the DB key; re-throw mapped error for safety.
            throw Failure.KeystoreInvalidated(e)
        }
        return when (val r = aead.decrypt(secretKey, wrapped)) {
            is Outcome.Success -> {
                wrapped.wipe()
                r.value
            }
            is Outcome.Failure -> {
                wrapped.wipe()
                // We DO NOT auto-delete the keyFile here: a silent wipe = silent data loss.
                // The caller surfaces a recovery UI instead.
                throw Failure.WrapCorrupted()
            }
        }
    }
}
