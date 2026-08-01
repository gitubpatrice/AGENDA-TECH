package com.filestech.agenda_tech.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import com.filestech.agenda_tech.core.crypto.KeystoreManager
import timber.log.Timber
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Binds the biometric unlock to a Keystore key (audit F3).
 *
 * Before this, `onAuthenticationSucceeded` simply unlocked the UI. That callback is a statement by
 * the app's own process that authentication happened; on a device where that process can be
 * manipulated, nothing behind it had to be true. Handing `BiometricPrompt` a `CryptoObject` moves
 * the proof into the OS: the key is generated with `setUserAuthenticationRequired(true)` and no
 * validity window, so the cipher simply cannot complete an operation unless the TEE saw a Class 3
 * authentication for that very use. [verify] performs a real operation and reports whether it
 * worked, so the unlock is gated on the crypto succeeding rather than on the callback firing.
 *
 * This does **not** derive the database key from the biometric — the SQLCipher key stays wrapped by
 * its own Keystore key and is not touched here. The gate protects the screen; the data at rest is
 * protected independently.
 */
@Singleton
class BiometricGate @Inject constructor(
    private val keystoreManager: KeystoreManager,
) {

    /** What [prepare] could produce. */
    sealed interface Preparation {
        /** Hand [cipher] to `BiometricPrompt` inside a `CryptoObject`. */
        data class Ready(val cipher: Cipher) : Preparation

        /**
         * The key was destroyed because biometrics were re-enrolled on the device. The stale key has
         * been deleted; the caller must turn the biometric preference off and fall back to the PIN,
         * telling the user why rather than failing mutely.
         */
        data object Invalidated : Preparation

        /** The key could not be prepared at all (no secure hardware, or an unexpected failure). */
        data object Unavailable : Preparation
    }

    /**
     * Builds the cipher to authenticate against, creating the key on first use.
     *
     * Encryption is used rather than decryption because the gate stores nothing: there is no
     * ciphertext to keep in sync, and no state that could drift out of step with the key. The proof
     * we need is only that the OS let the operation happen at all.
     */
    fun prepare(): Preparation = try {
        val key = keystoreManager.getOrCreateBiometricGateKey()
        Preparation.Ready(Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) })
    } catch (e: KeyPermanentlyInvalidatedException) {
        // Enrolling a fingerprint invalidates the key by design. Drop it so the next enable creates a
        // fresh one instead of failing forever on a key that can never work again.
        Timber.w(e, "BiometricGate: key invalidated by re-enrolment — dropping it")
        keystoreManager.deleteKey(KeystoreManager.ALIAS_BIOMETRIC_GATE)
        Preparation.Invalidated
    } catch (e: Exception) {
        // Deliberately broad, matching DatabaseFactory: Keystore generation goes through an IPC to
        // keystore2 and some OEM builds surface failures as unchecked ProviderException /
        // KeyStoreException, which a GeneralSecurityException catch would let through. A Keystore
        // that misbehaves must degrade to "no biometric unlock", never crash the unlock path.
        Timber.w(e, "BiometricGate: could not prepare the gate cipher")
        Preparation.Unavailable
    }

    /**
     * Completes the operation on the cipher `BiometricPrompt` handed back, returning true only if it
     * actually ran. A caller that unlocks on anything but true is back to trusting the callback.
     */
    fun verify(cipher: Cipher?): Boolean {
        if (cipher == null) {
            Timber.w("BiometricGate: authentication returned no cipher — refusing the unlock")
            return false
        }
        return try {
            cipher.doFinal(PROOF)
            true
        } catch (e: Exception) {
            // Same breadth, same reason as prepare(). Includes the narrow race where biometrics are
            // re-enrolled while the prompt is on screen: the key dies between init and doFinal, and
            // the only correct answer is to refuse this unlock rather than to crash.
            Timber.w(e, "BiometricGate: the authenticated cipher failed — refusing the unlock")
            false
        }
    }

    /** Forgets the gate key, so disabling and re-enabling biometrics starts from a clean one. */
    fun reset() = keystoreManager.deleteKey(KeystoreManager.ALIAS_BIOMETRIC_GATE)

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** Arbitrary plaintext: only whether the operation is permitted carries meaning. */
        val PROOF = "agenda-tech-unlock".toByteArray()
    }
}
