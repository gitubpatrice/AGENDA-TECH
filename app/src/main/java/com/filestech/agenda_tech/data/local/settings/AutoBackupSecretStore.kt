package com.filestech.agenda_tech.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.filestech.agenda_tech.core.crypto.AeadCipher
import com.filestech.agenda_tech.core.crypto.KeystoreManager
import com.filestech.agenda_tech.core.crypto.wipe
import com.filestech.agenda_tech.core.result.Outcome
import com.filestech.agenda_tech.di.IoDispatcher
import com.filestech.agenda_tech.domain.backup.AutoBackupSecret
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the automatic-backup password wrapped by its own AndroidKeyStore key.
 *
 * Same at-rest pattern as [LockRepositoryImpl] and `DatabaseKeyManager`, and a **separate alias** on
 * purpose: turning the app lock off deletes the PIN key, and it must not take the backup password
 * with it — those are two unrelated decisions by the user.
 *
 * Unlike the PIN, this is stored **reversibly**. A PIN only ever needs comparing, so it is hashed and
 * nothing can recover it; this password has to come back out to seal a file, so it is encrypted
 * instead. That is the cost [AutoBackupSecret] documents, and it is the price of a backup that can
 * still be opened once the phone is gone.
 *
 * Nothing here ever builds a `String` from the password: a String would sit in the heap until the GC
 * decides otherwise, with no way to clear it.
 */
@Singleton
class AutoBackupSecretStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val keystore: KeystoreManager,
    private val aead: AeadCipher,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AutoBackupSecret {

    override suspend fun isSet(): Boolean = withContext(io) { read()?.also { it.wipe() } != null }

    override suspend fun store(password: CharArray): Boolean = withContext(io) {
        val bytes = password.toUtf8Bytes()
        password.wipe()
        val wrapped = try {
            wrap(bytes)
        } finally {
            bytes.wipe()
        }
        if (wrapped == null) return@withContext false
        dataStore.edit { it[Keys.PASSWORD_WRAP] = Base64.getEncoder().encodeToString(wrapped) }
        true
    }

    override suspend fun read(): CharArray? = withContext(io) {
        val stored = dataStore.data.first()[Keys.PASSWORD_WRAP]
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            ?: return@withContext null
        val bytes = unwrap(stored) ?: return@withContext null
        try {
            bytes.toUtf8Chars()
        } finally {
            bytes.wipe()
        }
    }

    override suspend fun clear() = withContext(io) {
        // Deleting the key matters more than removing the entry: without it, the stored blob stays
        // decryptable by anyone who later restores the DataStore file onto this same device.
        keystore.deleteKey(KeystoreManager.ALIAS_AUTOBACKUP_PW)
        dataStore.edit { it.remove(Keys.PASSWORD_WRAP) }
        Unit
    }

    private fun wrap(blob: ByteArray): ByteArray? {
        val key = runCatching {
            keystore.getOrCreateKey(KeystoreManager.ALIAS_AUTOBACKUP_PW, allowUserIv = true)
        }.getOrElse {
            Timber.e(it, "AutoBackupSecret: Keystore unavailable, cannot wrap password")
            return null
        }
        return when (val r = aead.encrypt(key, blob)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> {
                Timber.e("AutoBackupSecret: password wrap encryption failed")
                null
            }
        }
    }

    /** Null when the key is gone or invalidated — a wiped Keystore, or a restored file from elsewhere. */
    private fun unwrap(wrapped: ByteArray): ByteArray? {
        val key = runCatching {
            keystore.getOrCreateKey(KeystoreManager.ALIAS_AUTOBACKUP_PW, allowUserIv = true)
        }.getOrNull() ?: return null
        return when (val r = aead.decrypt(key, wrapped)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> null
        }
    }

    /** UTF-8 without a String in between; the intermediate buffer is scrubbed. */
    private fun CharArray.toUtf8Bytes(): ByteArray {
        val buffer: ByteBuffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(this))
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        if (buffer.hasArray()) buffer.array().wipe()
        return out
    }

    private fun ByteArray.toUtf8Chars(): CharArray {
        val buffer: CharBuffer = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(this))
        val out = CharArray(buffer.remaining())
        buffer.get(out)
        if (buffer.hasArray()) buffer.array().wipe()
        return out
    }

    private object Keys {
        val PASSWORD_WRAP = stringPreferencesKey("auto_backup_password_wrap")
    }
}
