package com.filestech.agenda_tech.domain.repository

import kotlinx.coroutines.flow.Flow

/** Stores the app-lock state: whether a PIN lock is enabled, the salted PIN hash, and biometrics. */
interface LockRepository {

    val lockEnabled: Flow<Boolean>
    val biometricEnabled: Flow<Boolean>

    suspend fun isLockEnabled(): Boolean

    /** Sets the PIN (salted-hashed) and enables the lock. */
    /**
     * Enregistre le PIN. Rend **false** si le Keystore a refuse d'envelopper l'empreinte —
     * rien n'est alors ecrit (audit AG-10).
     *
     * Rendait `Unit`, et l'implementation sortait en silence sur ce chemin. L'appelant ne
     * pouvait donc rien dire : la boite de dialogue se refermait normalement, et selon le cas
     * le verrou n'etait pas active, ou l'ancien PIN restait le bon. Le jumeau
     * `AutoBackupSecret.store()` rend un Boolean pour exactement cette raison, et son ecran
     * affiche l'echec.
     */
    suspend fun setPin(pin: String): Boolean

    suspend fun verifyPin(pin: String): Boolean

    /** Clears the PIN and disables both the lock and biometrics. */
    suspend fun disableLock()

    suspend fun setBiometricEnabled(enabled: Boolean)
}
