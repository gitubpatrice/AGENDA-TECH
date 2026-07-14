package com.filestech.agenda_tech.security

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the app UI is currently gated behind the lock. */
enum class LockState { UNKNOWN, LOCKED, UNLOCKED }

/**
 * Holds the transient app-lock state for the process. Starts [LockState.UNKNOWN] until the Activity
 * resolves whether a lock is configured, then flips to LOCKED (show the lock screen) or UNLOCKED.
 * Re-locks when the app goes to the background.
 *
 * LOCK-4: also owns the in-memory PIN brute-force throttle. A PIN has a tiny keyspace, so after a
 * few wrong attempts we impose an escalating back-off before the next attempt is accepted. State is
 * process-scoped (resets on process death) — deliberately not persisted, so a forgotten lock-out can
 * never brick the app, and the back-off uses the monotonic [SystemClock.elapsedRealtime] clock so it
 * cannot be defeated by changing the wall clock.
 */
@Singleton
class AppLockManager @Inject constructor() {

    /**
     * Monotonic clock, overridable in tests. A plain injectable would need a Hilt binding for
     * `() -> Long`; a `@VisibleForTesting` seam keeps production DI parameter-free while letting the
     * back-off logic be driven by a fake clock.
     */
    @VisibleForTesting
    internal var nowMs: () -> Long = SystemClock::elapsedRealtime

    private val _state = MutableStateFlow(LockState.UNKNOWN)
    val state: StateFlow<LockState> = _state.asStateFlow()

    private var failedAttempts = 0
    private var lockedUntilElapsedMs = 0L

    fun lock() {
        _state.value = LockState.LOCKED
    }

    fun unlock() {
        _state.value = LockState.UNLOCKED
    }

    /** Remaining throttle time in ms before another PIN attempt is accepted (0 if none). */
    fun throttleRemainingMs(): Long =
        (lockedUntilElapsedMs - nowMs()).coerceAtLeast(0L)

    fun registerFailedAttempt() {
        failedAttempts++
        if (failedAttempts >= FREE_ATTEMPTS) {
            val step = (failedAttempts - FREE_ATTEMPTS + 1).coerceAtMost(MAX_BACKOFF_STEPS)
            lockedUntilElapsedMs = nowMs() + step * BACKOFF_UNIT_MS
        }
    }

    fun resetAttempts() {
        failedAttempts = 0
        lockedUntilElapsedMs = 0L
    }

    private companion object {
        /** Wrong attempts allowed before the back-off kicks in. */
        const val FREE_ATTEMPTS = 5
        const val BACKOFF_UNIT_MS = 10_000L // 10s, 20s, 30s … per extra failure
        const val MAX_BACKOFF_STEPS = 6 // capped at 60s
    }
}
