package com.filestech.agenda_tech.core.crypto

import com.filestech.agenda_tech.core.result.AppError
import com.filestech.agenda_tech.core.result.Outcome
import com.filestech.agenda_tech.core.result.runCatchingOutcome
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-256-GCM AEAD wrapper. Public output format:
 *
 *   [version:1][iv:12][ciphertext+tag:N]
 *
 * - Version 0x01 = AES-256-GCM, 128-bit auth tag
 * - IV is always 12 bytes (GCM recommended size)
 *
 * Used to wrap the SQLCipher master key with an AndroidKeyStore-held key
 * (cf. [com.filestech.agenda_tech.data.local.db.DatabaseKeyManager]).
 */
@Singleton
class AeadCipher @Inject constructor() {

    private val secureRandom = SecureRandom()

    fun encrypt(key: SecretKey, plaintext: ByteArray, aad: ByteArray? = null): Outcome<ByteArray> =
        runCatchingOutcome(
            block = {
                val iv = ByteArray(IV_SIZE).also(secureRandom::nextBytes)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                aad?.let { cipher.updateAAD(it) }
                val ct = cipher.doFinal(plaintext)
                ByteArray(1 + IV_SIZE + ct.size).also { out ->
                    out[0] = VERSION
                    System.arraycopy(iv, 0, out, 1, IV_SIZE)
                    System.arraycopy(ct, 0, out, 1 + IV_SIZE, ct.size)
                }
            },
            errorMapper = { AppError.Crypto("encrypt failed", it) },
        )

    fun decrypt(key: SecretKey, blob: ByteArray, aad: ByteArray? = null): Outcome<ByteArray> =
        runCatchingOutcome(
            block = {
                require(blob.isNotEmpty() && blob[0] == VERSION) { "Unsupported AEAD version" }
                require(blob.size > 1 + IV_SIZE) { "Blob too short" }
                val iv = ByteArray(IV_SIZE).also { System.arraycopy(blob, 1, it, 0, IV_SIZE) }
                val ctSize = blob.size - 1 - IV_SIZE
                val ct = ByteArray(ctSize).also { System.arraycopy(blob, 1 + IV_SIZE, it, 0, ctSize) }
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                aad?.let { cipher.updateAAD(it) }
                cipher.doFinal(ct)
            },
            errorMapper = { AppError.Crypto("decrypt failed", it) },
        )

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
        const val KEY_BYTES = 32
        const val VERSION: Byte = 0x01
    }
}
