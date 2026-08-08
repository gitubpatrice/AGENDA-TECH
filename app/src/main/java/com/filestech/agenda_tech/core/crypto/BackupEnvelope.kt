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
        val what = recognise(file)
        if (what !is Recognition.Openable) {
            password.wipe()
            return Outcome.Failure(AppError.Crypto(refusalReason(what)))
        }
        val header = what.header
        val key = deriveKey(password, header.salt, header.iterations)
            ?: return Outcome.Failure(AppError.Crypto("key derivation failed"))
        return try {
            val body = file.copyOfRange(header.size, file.size)
            aead.decrypt(key, body, aad = file.copyOfRange(0, header.size))
        } finally {
            key.wipeIfPossible()
        }
    }

    /**
     * What [file] turned out to be, decided on header bytes alone — before any key is derived, so
     * the answer reveals nothing about the password.
     *
     * The four cases are kept apart because they call for **four different things from the user**,
     * and collapsing them is how a correct backup gets thrown away: told "this is not a backup"
     * about the only copy of their agenda, a reasonable person deletes the file.
     */
    sealed interface Recognition {
        /** No magic bytes: the user picked something that was never an `.atbak`. */
        data object NotABackup : Recognition

        /**
         * Ours by its magic bytes, and its envelope version or KDF id is **higher** than anything
         * this build knows: it was written by a newer Agenda Tech. The user must update, not
         * re-export and certainly not delete.
         *
         * Says nothing about the file being intact — no authentication has happened at this point.
         * It only says this build could not read it even if it were.
         */
        data object UnsupportedVersion : Recognition

        /** Ours, of a version we read, but the header does not hold up: truncated or out of range. */
        data object Malformed : Recognition

        /** Ours, and the header holds up. Only the password is left to check. */
        class Openable internal constructor(internal val header: Header) : Recognition
    }

    /** @see Recognition */
    fun recognise(file: ByteArray): Recognition = parseHeader(file)

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

    /**
     * The log-facing half of [Recognition]. The user-facing half lives in the UI, in three separate
     * strings — the whole point of the split is that these three do not become one sentence again.
     */
    private fun refusalReason(what: Recognition): String = when (what) {
        Recognition.NotABackup -> "not an Agenda Tech backup"
        Recognition.UnsupportedVersion -> "backup written by a newer Agenda Tech"
        Recognition.Malformed -> "backup header is truncated or out of range"
        is Recognition.Openable -> error("Openable is not a refusal")
    }

    // Guard clauses over an untrusted header: each `return` rejects one specific malformation, and
    // naming them separately is what makes a hostile file refusable without a single ambiguous branch.
    @Suppress("ReturnCount")
    private fun parseHeader(file: ByteArray): Recognition {
        // The magic is asked first and alone: it is the only question whose honest answer is "you
        // picked the wrong file". Every check after it is about a file that IS ours, and must never
        // be reported as if it belonged to someone else.
        if (file.size < MAGIC.size) return Recognition.NotABackup
        if (!file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return Recognition.NotABackup

        // Everything up to and including the saltLen byte: magic(5) + envVersion + kdfId + saltLen (3)
        // + iterations(4). The salt itself follows.
        val preSalt = MAGIC.size + 3 + Int.SIZE_BYTES
        if (file.size < preSalt) return Recognition.Malformed
        // Split from the wrap(): on the Android platform signature `position()` returns `Buffer`,
        // which would erase the ByteBuffer type and the typed getters with it.
        val buf = ByteBuffer.wrap(file)
        buf.position(MAGIC.size)
        // The class KDoc promises this format can move to another KDF "without invalidating files
        // already written". That promise has a mirror image nothing was enforcing: an *older* build
        // meeting a *newer* file. It used to answer "this is not an Agenda Tech backup" — about a
        // perfectly intact backup, to a user for whom it is the only copy of their agenda.
        // `>` and not `!=`, on both bytes. An inequality test would answer "made by a newer Agenda
        // Tech — update the app" to someone already on the newest build, whenever the byte is merely
        // *wrong*: a flipped bit at offset 5 or 6 of an otherwise damaged file reads exactly like a
        // future version. They would then wait for an update that is never coming, instead of trying
        // another copy. Only a value ABOVE what we know can honestly be called newer; anything else
        // is damage, and `Malformed` says so.
        val envelopeVersion = buf.get()
        if (envelopeVersion > ENVELOPE_VERSION) return Recognition.UnsupportedVersion
        if (envelopeVersion != ENVELOPE_VERSION) return Recognition.Malformed
        val kdfId = buf.get()
        if (kdfId > KDF_PBKDF2_HMAC_SHA256) return Recognition.UnsupportedVersion
        if (kdfId != KDF_PBKDF2_HMAC_SHA256) return Recognition.Malformed
        val iterations = buf.int
        // A hostile file could ask for billions of rounds and hang the app until the user force-quits.
        // The floor matters just as much: it would silently produce a key nobody had to work for.
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return Recognition.Malformed
        val saltLen = buf.get().toInt()
        if (saltLen !in MIN_SALT_BYTES..MAX_SALT_BYTES) return Recognition.Malformed
        if (file.size < preSalt + saltLen) return Recognition.Malformed
        val salt = ByteArray(saltLen).also(buf::get)
        return Recognition.Openable(Header(salt = salt, iterations = iterations, size = preSalt + saltLen))
    }

    internal data class Header(val salt: ByteArray, val iterations: Int, val size: Int)

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
