package com.filestech.agenda_tech.core.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * When a missing Keystore key may be treated as **gone** — the decision that, for the database key,
 * separates "refuse to open, keep the agenda" from "reset the agenda".
 *
 * On API 26–30 `AndroidKeyStoreSpi.engineGetKey` returns null whenever `KeyStore.contains()` is false,
 * and `contains()` answers false on a `RemoteException` from the keystore daemon. A null there is
 * therefore not proof of absence, and treating it as such erased agendas whose key was intact. From
 * API 31 (keystore2) null means `KEY_NOT_FOUND` and nothing else.
 *
 * On the JVM on purpose: the instrumented tests run on API 29 (the CI emulator and the Galaxy S9), so
 * the API 31+ branch would otherwise be executed by no test at all.
 */
class KeystoreManagerMissingKeyTest {

    @ParameterizedTest
    @ValueSource(ints = [26, 27, 28, 29, 30])
    fun `before keystore2 a missing key is NOT proof that it is gone`(sdkInt: Int) {
        assertThat(KeystoreManager.missingKeyIsConclusive(sdkInt)).isFalse()
    }

    @ParameterizedTest
    @ValueSource(ints = [31, 32, 33, 34, 35, 36, 37])
    fun `from keystore2 a missing key really is gone`(sdkInt: Int) {
        assertThat(KeystoreManager.missingKeyIsConclusive(sdkInt)).isTrue()
    }
}
