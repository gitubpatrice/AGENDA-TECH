package com.filestech.agenda_tech.data.local.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.agenda_tech.core.crypto.AeadCipher
import com.filestech.agenda_tech.core.crypto.KeystoreManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Reading a sealed secret must never **create** the key it was sealed with.
 *
 * ## The defect this locks down
 *
 * `LockRepositoryImpl.unwrap` and `AutoBackupSecretStore.unwrap` obtained their key through
 * `KeystoreManager.getOrCreateKey`, which generates one whenever `KeyStore.getKey` returns null. On
 * API 26–30 that null also comes back when the keystore daemon is unreachable for a moment. The key
 * was then replaced under the same alias: the PIN could never be verified again (the lock screen has
 * no way around it), and the backup password was lost.
 *
 * ## How it is observed
 *
 * An unreachable daemon cannot be provoked from a test, but its consequence can: the alias is deleted,
 * the secret is read, and the test asserts that **no key was created** under that alias. That is the
 * assertion the old code fails — `getOrCreateKey` recreated it on the first read.
 *
 * Real Keystore, real DataStore, real AEAD; no mock. Run on the Galaxy S9 (API 29) by serial, never
 * via `connectedAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class WrappedSecretKeyLossTest {

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var dataStoreName: String
    private lateinit var dataStore: DataStore<Preferences>
    private val keystore = KeystoreManager()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // One file per test: DataStore forbids two live instances on the same file in a process.
        dataStoreName = "key-loss-test-" + UUID.randomUUID()
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            context.preferencesDataStoreFile(dataStoreName)
        }
        keystore.deleteKey(KeystoreManager.ALIAS_PIN_WRAP)
        keystore.deleteKey(KeystoreManager.ALIAS_AUTOBACKUP_PW)
    }

    @After
    fun tearDown() {
        scope.cancel()
        context.preferencesDataStoreFile(dataStoreName).delete()
        keystore.deleteKey(KeystoreManager.ALIAS_PIN_WRAP)
        keystore.deleteKey(KeystoreManager.ALIAS_AUTOBACKUP_PW)
    }

    @Test
    fun verifyingAPinNeverCreatesTheKeyItWasSealedWith() = runBlocking {
        val repository = LockRepositoryImpl(dataStore, keystore, AeadCipher(), Dispatchers.IO)
        assertThat(repository.setPin("2468")).isTrue()
        // The positive half: while the key exists, the PIN verifies — so the refusal below is about the
        // key, not a repository that cannot verify anything.
        assertThat(repository.verifyPin("2468")).isTrue()

        keystore.deleteKey(KeystoreManager.ALIAS_PIN_WRAP)

        assertThat(repository.verifyPin("2468")).isFalse()
        assertThat(keystore.containsAlias(KeystoreManager.ALIAS_PIN_WRAP)).isFalse()
    }

    @Test
    fun readingTheBackupPasswordNeverCreatesTheKeyItWasSealedWith() = runBlocking {
        val store = AutoBackupSecretStore(dataStore, keystore, AeadCipher(), Dispatchers.IO)
        assertThat(store.store("mot de passe".toCharArray())).isTrue()
        assertThat(store.read()?.concatToString()).isEqualTo("mot de passe")

        keystore.deleteKey(KeystoreManager.ALIAS_AUTOBACKUP_PW)

        assertThat(store.read()).isNull()
        assertThat(keystore.containsAlias(KeystoreManager.ALIAS_AUTOBACKUP_PW)).isFalse()
    }
}
