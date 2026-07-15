package com.filestech.agenda_tech.core.crypto

import com.filestech.agenda_tech.core.result.AppError
import com.filestech.agenda_tech.core.result.Outcome
import com.filestech.agenda_tech.core.result.map
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The `.atbak` file format: a password-derived key wrapped around the audited [AeadCipher].
 *
 * ```
 *   [magic:5 "ATBAK"][envVersion:1][kdfId:1][iterations:4 BE][saltLen:1][salt:16]  ← header, 28 bytes
 *   [AeadCipher blob: version:1 | iv:12 | ciphertext+tag]                          ← body
 * ```
 *
 * **The header is passed as GCM additional authenticated data.** It is not secret, but it is
 * authenticated: flipping a bit of the salt, or rewriting `iterations` down to 1 to make an offline
 * attack cheap, breaks the auth tag and the file refuses to open. Without this, the header would be
 * attacker-malleable metadata sitting in front of otherwise sound ciphertext.
 *
 * The KDF is identified by a byte and its cost is stored explicitly rather than implied, so both can
 * change (notably to Argon2id) without invalidating files already written — a backup must still open
 * years later, which is the whole point of the artefact.
 *
 * Key derivation is deliberate CPU work (~1s). Never call this on the main thread.
 */
@Singleton
class BackupEnvelope @Inject constructor(
    private val aead: AeadCipher,
) {

    private val secureRandom = SecureRandom()

    /** Encrypts [plaintext] under [password]. The caller's [password] is wiped. */
    fun seal(password: CharArray, plaintext: ByteArray): Outcome<ByteArray> {
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val header = buildHeader(kdfId = KDF_PBKDF2_HMAC_SHA256, iterations = ITERATIONS, salt = salt)
        val key = deriveKey(password, salt, ITERATIONS)
            ?: return Outcome.Failure(AppError.Crypto("key derivation failed"))
        return try {
            aead.encrypt(key, plaintext, aad = header).map { body -> header + body }
        } finally {
            key.wipeIfPossible()
        }
    }

    /**
     * Decrypts an `.atbak` file. A wrong password is indistinguishable from a corrupt file — both
     * surface as a GCM tag mismatch — so callers must phrase the error as "wrong password **or**
     * damaged file" rather than confirming which.
     *
     * The caller's [password] is wiped.
     */
    fun open(password: CharArray, file: ByteArray): Outcome<ByteArray> {
        val header = parseHeader(file)
            ?: run { password.wipe(); return Outcome.Failure(AppError.Crypto("not an Agenda Tech backup")) }
        val key = deriveKey(password, header.salt, header.iterations)
            ?: return Outcome.Failure(AppError.Crypto("key derivation failed"))
        return try {
            val body = file.copyOfRange(header.size, file.size)
            aead.decrypt(key, body, aad = file.copyOfRange(0, header.size))
        } finally {
            key.wipeIfPossible()
        }
    }

    /** True when [file] at least *looks* like an `.atbak` — used to reject a wrong pick before asking for a password. */
    fun isRecognised(file: ByteArray): Boolean = parseHeader(file) != null

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKey? {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            val bits = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
            try {
                SecretKeySpec(bits, "AES")
            } finally {
                bits.wipe()
            }
        } catch (_: Exception) {
            null
        } finally {
            // PBEKeySpec clones the password, so clearing the spec only scrubs that clone.
            spec.clearPassword()
            password.wipe()
        }
    }

    private fun buildHeader(kdfId: Byte, iterations: Int, salt: ByteArray): ByteArray =
        ByteBuffer.allocate(MAGIC.size + 3 + Int.SIZE_BYTES + salt.size).apply {
            put(MAGIC)
            put(ENVELOPE_VERSION)
            put(kdfId)
            putInt(iterations)
            put(salt.size.toByte())
            put(salt)
        }.array()

    private fun parseHeader(file: ByteArray): Header? {
        // Everything up to and including the saltLen byte: magic(5) + envVersion + kdfId + saltLen (3)
        // + iterations(4). The salt itself follows.
        val preSalt = MAGIC.size + 3 + Int.SIZE_BYTES
        if (file.size < preSalt) return null
        if (!file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        // Split from the wrap(): on the Android platform signature `position()` returns `Buffer`,
        // which would erase the ByteBuffer type and the typed getters with it.
        val buf = ByteBuffer.wrap(file)
        buf.position(MAGIC.size)
        if (buf.get() != ENVELOPE_VERSION) return null
        if (buf.get() != KDF_PBKDF2_HMAC_SHA256) return null
        val iterations = buf.int
        // A hostile file could ask for billions of rounds and hang the app until the user force-quits.
        // The floor matters just as much: it would silently produce a key nobody had to work for.
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return null
        val saltLen = buf.get().toInt()
        if (saltLen !in MIN_SALT_BYTES..MAX_SALT_BYTES) return null
        if (file.size < preSalt + saltLen) return null
        val salt = ByteArray(saltLen).also(buf::get)
        return Header(salt = salt, iterations = iterations, size = preSalt + saltLen)
    }

    private data class Header(val salt: ByteArray, val iterations: Int, val size: Int)

    private fun SecretKey.wipeIfPossible() {
        // SecretKeySpec copies the bytes in and hands a fresh copy back from `encoded`, so there is no
        // handle on its internal array. Kept as a destroy() attempt — genuinely best-effort.
        runCatching { destroy() }
    }

    companion object {
        /** `ATBAK` — lets a wrong file be rejected before the user is asked for a password. */
        private val MAGIC = byteArrayOf(0x41, 0x54, 0x42, 0x41, 0x4B)
        private const val ENVELOPE_VERSION: Byte = 0x01
        private const val KDF_PBKDF2_HMAC_SHA256: Byte = 0x01
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"

        /** OWASP's 2023+ floor for PBKDF2-HMAC-SHA256. ~1 s on a mid-range phone. */
        const val ITERATIONS = 600_000
        private const val MIN_ITERATIONS = 100_000
        private const val MAX_ITERATIONS = 10_000_000
        private const val SALT_BYTES = 16
        private const val MIN_SALT_BYTES = 16
        private const val MAX_SALT_BYTES = 64
        private const val KEY_BITS = 256

        /** Below this a password is not worth the 600k rounds; enforced at the UI. */
        const val MIN_PASSWORD_LENGTH = 12
    }
}
