package com.filestech.agenda_tech.core.crypto

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
                if (userAuthRequired && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
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
    }
}
