package com.filestech.agenda_tech.domain.repository

import kotlinx.coroutines.flow.Flow

/** Stores the app-lock state: whether a PIN lock is enabled, the salted PIN hash, and biometrics. */
interface LockRepository {

    val lockEnabled: Flow<Boolean>
    val biometricEnabled: Flow<Boolean>

    suspend fun isLockEnabled(): Boolean

    /** Sets the PIN (salted-hashed) and enables the lock. */
    suspend fun setPin(pin: String)

    suspend fun verifyPin(pin: String): Boolean

    /** Clears the PIN and disables both the lock and biometrics. */
    suspend fun disableLock()

    suspend fun setBiometricEnabled(enabled: Boolean)
}
