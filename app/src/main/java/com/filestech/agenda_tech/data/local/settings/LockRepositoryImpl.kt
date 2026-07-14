package com.filestech.agenda_tech.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.filestech.agenda_tech.core.crypto.AeadCipher
import com.filestech.agenda_tech.core.crypto.KeystoreManager
import com.filestech.agenda_tech.core.crypto.PinHasher
import com.filestech.agenda_tech.core.crypto.wipe
import com.filestech.agenda_tech.core.result.Outcome
import com.filestech.agenda_tech.di.IoDispatcher
import com.filestech.agenda_tech.domain.repository.LockRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the app-lock secret. The PIN itself is never stored — only a salted PBKDF2 hash.
 *
 * LOCK-1: the `salt || hash` blob is additionally wrapped with an AndroidKeyStore AES-GCM key
 * (hardware-backed on devices with a TEE, non-exportable) before landing in the unencrypted
 * DataStore file — the same at-rest pattern as [com.filestech.agenda_tech.data.local.db.DatabaseKeyManager].
 * A PIN has a tiny keyspace, so plaintext salt+hash on disk would be brute-forceable offline in
 * seconds; the Keystore wrap means a mere file exfiltration (backup, file-read exploit) can't reach it.
 */
@Singleton
class LockRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val keystore: KeystoreManager,
    private val aead: AeadCipher,
    @IoDispatcher private val io: CoroutineDispatcher,
) : LockRepository {

    override val lockEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.LOCK_ENABLED] ?: false }
    override val biometricEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.BIOMETRIC_ENABLED] ?: false }

    override suspend fun isLockEnabled(): Boolean = lockEnabled.first()

    override suspend fun setPin(pin: String) = withContext(io) {
        val salt = PinHasher.newSalt()
        val pinChars = pin.toCharArray()
        val hash = PinHasher.hash(pinChars, salt) // hash() wipes pinChars in its finally
        val blob = salt + hash
        val wrapped = wrap(blob)
        blob.wipe()
        hash.wipe()
        if (wrapped == null) return@withContext
        dataStore.edit { prefs ->
            prefs[Keys.PIN_WRAP] = Base64.getEncoder().encodeToString(wrapped)
            prefs[Keys.LOCK_ENABLED] = true
        }
        Unit
    }

    override suspend fun verifyPin(pin: String): Boolean = withContext(io) {
        val stored = dataStore.data.first()[Keys.PIN_WRAP]
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            ?: return@withContext false
        val blob = unwrap(stored) ?: return@withContext false
        try {
            if (blob.size != SALT_BYTES + HASH_BYTES) return@withContext false
            val salt = blob.copyOfRange(0, SALT_BYTES)
            val hash = blob.copyOfRange(SALT_BYTES, blob.size)
            val ok = PinHasher.matches(pin.toCharArray(), salt, hash) // matches() wipes its CharArray
            hash.wipe()
            ok
        } finally {
            blob.wipe()
        }
    }

    override suspend fun disableLock() {
        keystore.deleteKey(KeystoreManager.ALIAS_PIN_WRAP)
        dataStore.edit { prefs ->
            prefs.remove(Keys.PIN_WRAP)
            prefs[Keys.LOCK_ENABLED] = false
            prefs[Keys.BIOMETRIC_ENABLED] = false
        }
    }

    override suspend fun setBiometricEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BIOMETRIC_ENABLED] = enabled }
    }

    /** Encrypts the salt+hash blob under a fresh-or-existing Keystore key; null on Keystore/crypto failure. */
    private fun wrap(blob: ByteArray): ByteArray? {
        val key = runCatching {
            keystore.getOrCreateKey(KeystoreManager.ALIAS_PIN_WRAP, allowUserIv = true)
        }.getOrElse {
            Timber.e(it, "LockRepository: Keystore unavailable, cannot wrap PIN")
            return null
        }
        return when (val r = aead.encrypt(key, blob)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> {
                Timber.e("LockRepository: PIN wrap encryption failed")
                null
            }
        }
    }

    /** Decrypts a stored wrapped blob; null if the Keystore key is gone/invalidated or decryption fails. */
    private fun unwrap(wrapped: ByteArray): ByteArray? {
        val key = runCatching {
            keystore.getOrCreateKey(KeystoreManager.ALIAS_PIN_WRAP, allowUserIv = true)
        }.getOrNull() ?: return null
        return when (val r = aead.decrypt(key, wrapped)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> null
        }
    }

    private object Keys {
        val LOCK_ENABLED = booleanPreferencesKey("lock_enabled")
        val BIOMETRIC_ENABLED = booleanPreferencesKey("lock_biometric_enabled")
        val PIN_WRAP = stringPreferencesKey("lock_pin_wrap")
    }

    private companion object {
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32
    }
}
