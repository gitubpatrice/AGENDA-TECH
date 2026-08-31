package com.filestech.agenda_tech.domain.backup

/**
 * The password automatic backups are sealed with.
 *
 * A `.atbak` is encrypted by a password the user chooses, and a backup that runs by itself cannot ask
 * for it every week. So it is stored — wrapped by an AndroidKeyStore key, hardware-backed where a TEE
 * exists — and that is a deliberate trade the user makes when they turn the feature on:
 *
 *  - **What it costs.** The password now exists on the device, where before it existed only in the
 *    user's head. Someone who fully compromises the running device can make the app decrypt it.
 *  - **What it buys.** The file stays readable by hand, on any machine, with that password — which is
 *    the whole point. A key that never left this phone would produce backups that die with it, and a
 *    backup exists precisely for the day the phone is gone.
 *
 * The wrap means a stolen *file* — a DataStore copy, a file-read exploit, an adb backup — is not
 * enough: the Keystore key is non-exportable and never leaves the device.
 */
interface AutoBackupSecret {

    /** True when a password has been stored and the Keystore key that wraps it is still valid. */
    suspend fun isSet(): Boolean

    /** Stores [password], wrapped. The caller's array is wiped, even on failure. Returns false if the Keystore refused. */
    suspend fun store(password: CharArray): Boolean

    /**
     * A fresh copy of the stored password, or null when there is none or the Keystore key is gone
     * (a factory reset of the secure hardware, or the key being invalidated).
     *
     * A **new array each call**, because the exporter wipes what it is given.
     */
    suspend fun read(): CharArray?

    /** Forgets the password and deletes the Keystore key that wrapped it. */
    suspend fun clear()
}
