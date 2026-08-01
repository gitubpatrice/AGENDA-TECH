package com.filestech.agenda_tech.core.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around the AndroidKeyStore for AES-256-GCM keys. Keys are hardware-backed on
 * devices with a TEE/StrongBox and are non-exportable.
 *
 * One key per logical purpose:
 *  - [ALIAS_DB_MASTER] : wraps the SQLCipher master key.
 *  - [ALIAS_PIN_WRAP] : wraps the salted app-lock PIN hash blob.
 *
 * `allowUserIv` (= `setRandomizedEncryptionRequired(false)`) defaults to **false**, i.e. the OS
 * enforces IV randomisation. [ALIAS_DB_MASTER] opts in to `true` because
 * [com.filestech.agenda_tech.core.crypto.AeadCipher] already provides a fresh SecureRandom IV.
 */
@Singleton
class KeystoreManager @Inject constructor() {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    fun getOrCreateKey(
        alias: String,
        userAuthRequired: Boolean = false,
        allowUserIv: Boolean = false,
    ): SecretKey {
        keyStore.getKey(alias, null)?.let { return it as SecretKey }
        return generateKey(alias, userAuthRequired, allowUserIv)
    }

    fun deleteKey(alias: String) {
        runCatching { keyStore.deleteEntry(alias) }
            .onFailure { Timber.w(it, "KeystoreManager: failed to delete %s", alias) }
    }

    fun containsAlias(alias: String): Boolean = keyStore.containsAlias(alias)

    /**
     * The key that backs the biometric unlock gate (audit F3).
     *
     * Distinct from [getOrCreateKey] because its whole point is to be *unusable* without a fresh
     * Class 3 authentication: no validity duration is set, so the OS demands one authentication per
     * use, which is what forces the caller through a `CryptoObject` instead of trusting a success
     * callback. `setInvalidatedByBiometricEnrollment(true)` means enrolling a new fingerprint
     * destroys it — that is the desired behaviour (someone who can add a fingerprint must not
     * inherit the unlock), and the caller has to be ready for
     * [android.security.keystore.KeyPermanentlyInvalidatedException] at cipher init.
     *
     * On API 30+ the accepted tier is pinned to `AUTH_BIOMETRIC_STRONG`, so device credential
     * cannot satisfy it — the same Class 3 policy [com.filestech.agenda_tech.security.StrongBiometrics]
     * states, enforced here by the OS rather than by our own check.
     */
    fun getOrCreateBiometricGateKey(): SecretKey {
        keyStore.getKey(ALIAS_BIOMETRIC_GATE, null)?.let { return it as SecretKey }
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS_BIOMETRIC_GATE,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
            }
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    private fun generateKey(alias: String, userAuthRequired: Boolean, allowUserIv: Boolean): SecretKey {
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(!allowUserIv)
            .setUserAuthenticationRequired(userAuthRequired)
            .apply {
                if (userAuthRequired && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInvalidatedByBiometricEnrollment(true)
                }
            }
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_SIZE_BITS = 256

        const val ALIAS_DB_MASTER = "agendatech_db_master"
        const val ALIAS_PIN_WRAP = "agendatech_pin_wrap"
        const val ALIAS_BIOMETRIC_GATE = "agendatech_biometric_gate"
    }
}
