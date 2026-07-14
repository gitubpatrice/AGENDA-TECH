package com.filestech.agenda_tech.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

// `wipe()` lives in the same package (SecureBytes.kt); referenced here for the caller-array scrub.

/**
 * Salted PBKDF2 hashing for the app-lock PIN. The PIN gates the UI only (the database is separately
 * SQLCipher-encrypted), but we still store a strong, salted hash — never the PIN — so reading the
 * (unencrypted) settings file does not reveal it.
 */
object PinHasher {

    private const val SALT_BYTES = 16
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    fun hash(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, ITERATIONS, KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return factory.generateSecret(spec).encoded
        } finally {
            // PBEKeySpec clones `pin` internally, so clearPassword() only scrubs that clone — the
            // caller's CharArray must be wiped separately.
            spec.clearPassword()
            pin.wipe()
        }
    }

    /** Constant-time comparison to avoid leaking match progress via timing. */
    fun matches(pin: CharArray, salt: ByteArray, expectedHash: ByteArray): Boolean {
        val actual = hash(pin, salt)
        return MessageDigest.isEqual(actual, expectedHash)
    }
}
