package com.filestech.agenda_tech.data.local.settings

import android.content.Context
import com.filestech.agenda_tech.security.LockThrottle
import com.filestech.agenda_tech.security.LockThrottleStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the PIN back-off in a private [android.content.SharedPreferences] file (audit SEC-2).
 *
 * SharedPreferences rather than the app's DataStore because [LockThrottleStore] is read and written
 * synchronously, on the path that decides whether a PIN attempt is even accepted. DataStore is
 * suspend-only, so consulting it would either turn the whole lock API async or leave a window at
 * startup where the throttle reads as "no back-off" — the exact window an attacker restarting the
 * process is trying to open.
 *
 * The file is app-private but not encrypted, so root or a physical extraction can clear it. That is
 * accepted: this defends the attempt counter against a `force-stop`, which needs no privileges at
 * all, not against an attacker who already owns the device (and who, at that point, is attacking
 * the SQLCipher-encrypted database directly rather than guessing a UI PIN).
 */
@Singleton
class SharedPrefsLockThrottleStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : LockThrottleStore {

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun load(): LockThrottle = LockThrottle(
        failedAttempts = prefs.getInt(KEY_ATTEMPTS, 0),
        lockedUntilWallMs = prefs.getLong(KEY_LOCKED_UNTIL, 0L),
    )

    override fun save(throttle: LockThrottle) {
        // commit(), not apply(): apply() hands the write to a background thread, and a force-stop
        // landing in that gap would drop exactly the failed attempt we are trying to remember.
        prefs.edit()
            .putInt(KEY_ATTEMPTS, throttle.failedAttempts)
            .putLong(KEY_LOCKED_UNTIL, throttle.lockedUntilWallMs)
            .commit()
    }

    private companion object {
        const val PREFS = "agendatech_lock_throttle"
        const val KEY_ATTEMPTS = "failed_attempts"
        const val KEY_LOCKED_UNTIL = "locked_until_wall_ms"
    }
}
