package com.filestech.agenda_tech.security

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.filestech.agenda_tech.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the app UI is currently gated behind the lock. */
enum class LockState { UNKNOWN, LOCKED, UNLOCKED }

/**
 * Holds the transient app-lock state for the process. Starts [LockState.UNKNOWN] until the Activity
 * resolves whether a lock is configured, then flips to LOCKED (show the lock screen) or UNLOCKED.
 * Re-locks when the app goes to the background.
 *
 * LOCK-4: also owns the PIN brute-force throttle. A PIN has a tiny keyspace, so after a few wrong
 * attempts we impose an escalating back-off before the next attempt is accepted.
 *
 * ## Why the throttle is persisted (audit SEC-2)
 *
 * This state used to be process-scoped on the grounds that a forgotten lock-out must never brick the
 * app. It does not need to be: the back-off is capped at [MAX_BACKOFF_STEPS] steps of
 * [BACKOFF_UNIT_MS], i.e. a minute, so persisting it cannot lock anyone out for longer than that.
 * Keeping it in memory, on the other hand, meant a `force-stop` — no root, no tools, a few taps in
 * Settings — reset the counter to zero, handing an attacker [FREE_ATTEMPTS] fresh attempts per
 * restart and defeating the escalation entirely.
 *
 * The live countdown still runs on the monotonic [SystemClock.elapsedRealtime] clock, so it cannot
 * be shortened by changing the wall clock. Only the *persisted* deadline is wall-clock, and it is
 * clamped on the way back in — see [LockThrottle].
 */
@Singleton
class AppLockManager @Inject constructor(
    private val store: LockThrottleStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Monotonic clock, overridable in tests. A plain injectable would need a Hilt binding for
     * `() -> Long`; a `@VisibleForTesting` seam keeps production DI parameter-free while letting the
     * back-off logic be driven by a fake clock.
     */
    @VisibleForTesting
    internal var nowMs: () -> Long = SystemClock::elapsedRealtime

    /** Wall clock, overridable in tests. Only ever used for the persisted deadline. */
    @VisibleForTesting
    internal var wallClockMs: () -> Long = System::currentTimeMillis

    private val _state = MutableStateFlow(LockState.UNKNOWN)
    val state: StateFlow<LockState> = _state.asStateFlow()

    /**
     * Serialises the whole read-modify-write (audit F6).
     *
     * Each entry point was a check-then-act over shared state with nothing held across the suspend
     * point, so two attempts racing - a fast double tap, or a caller not on the Main dispatcher -
     * could both read the same count and both write it back as one increment, spending two guesses
     * for the price of one and defeating the escalation the persistence was added to protect.
     */
    private val mutex = Mutex()

    private var failedAttempts = 0
    private var lockedUntilElapsedMs = 0L
    private var restored = false

    fun lock() {
        _state.value = LockState.LOCKED
    }

    fun unlock() {
        _state.value = LockState.UNLOCKED
    }

    /** What one call to [attemptPin] did. */
    sealed interface Attempt {
        /** The back-off was still running; no guess was spent. */
        data class Throttled(val remainingMs: Long) : Attempt
        data object Accepted : Attempt
        data class Rejected(val throttleMs: Long) : Attempt
    }

    /**
     * Spends **one** PIN guess: checks the back-off, verifies, and records the outcome — atomically.
     *
     * ## Why the verification happens inside the lock (audit S16)
     *
     * The mutex below was introduced by F6 to serialise the counter, and it did. But the decision
     * *whether an attempt is allowed at all* lived in the caller, split across two separate calls with
     * roughly 100 ms of PBKDF2 between them and no lock held:
     *
     * ```
     * if (appLock.throttleRemainingMs() > 0) return   // lock taken, then released
     * if (lockRepository.verifyPin(pin)) …            // ~120 000 rounds, unguarded
     * else appLock.registerFailedAttempt()            // lock taken again
     * ```
     *
     * Every coroutine launched inside that gap read a back-off of zero and got its own guess. The
     * counter still ended up correct — the mutex saw to that — but the back-off had not been applied
     * to any of them. Against the threat model SEC-2 was written for (someone holding the phone, e.g.
     * `adb shell input tap` in a loop), that turns one guess per 60 s window into a burst, and a
     * four-digit PIN from a week of brute force into hours.
     *
     * Passing the verification in as a lambda is what makes the whole read-modify-write one operation,
     * which is what the class KDoc claimed all along. It also keeps this class free of any dependency
     * on the PIN store, so it stays unit-testable with a fake.
     */
    suspend fun attemptPin(verify: suspend () -> Boolean): Attempt = withContext(io) {
        mutex.withLock {
            restoreOnce()
            val remaining = (lockedUntilElapsedMs - nowMs()).coerceAtLeast(0L)
            if (remaining > 0) return@withLock Attempt.Throttled(remaining)
            if (verify()) {
                resetLocked()
                Attempt.Accepted
            } else {
                registerFailedLocked()
                Attempt.Rejected((lockedUntilElapsedMs - nowMs()).coerceAtLeast(0L))
            }
        }
    }

    /**
     * Remaining throttle time in ms before another PIN attempt is accepted (0 if none).
     *
     * Read-only: it drives the visible countdown. Deciding whether a guess may be spent is
     * [attemptPin]'s job, and only its — asking here and acting later is the race S16 named.
     *
     * `suspend` because the first call reads the persisted back-off off disk, and the writes below
     * are durable (`commit`) by design — none of that may run on the Main thread.
     */
    suspend fun throttleRemainingMs(): Long = withContext(io) { mutex.withLock {
        restoreOnce()
        (lockedUntilElapsedMs - nowMs()).coerceAtLeast(0L)
    } }

    suspend fun registerFailedAttempt() = withContext(io) { mutex.withLock {
        restoreOnce()
        registerFailedLocked()
    } }

    suspend fun resetAttempts() = withContext(io) { mutex.withLock { resetLocked() } }

    /** The body of [registerFailedAttempt]; the caller must already hold [mutex] and have restored. */
    private fun registerFailedLocked() {
        failedAttempts++
        val backoffMs = if (failedAttempts >= FREE_ATTEMPTS) {
            val step = (failedAttempts - FREE_ATTEMPTS + 1).coerceAtMost(MAX_BACKOFF_STEPS)
            step * BACKOFF_UNIT_MS
        } else {
            0L
        }
        lockedUntilElapsedMs = if (backoffMs > 0) nowMs() + backoffMs else 0L
        store.save(
            LockThrottle(
                failedAttempts = failedAttempts,
                lockedUntilWallMs = if (backoffMs > 0) wallClockMs() + backoffMs else 0L,
            ),
        )
    }

    /** The body of [resetAttempts]; the caller must already hold [mutex]. */
    private fun resetLocked() {
        restored = true // a correct PIN settles the question; nothing left to restore
        failedAttempts = 0
        lockedUntilElapsedMs = 0L
        store.save(LockThrottle.NONE)
    }

    /**
     * Pulls the persisted back-off into memory on first use.
     *
     * Lazy rather than eager so that constructing the singleton — which Hilt may do early, on the
     * Main thread — never touches disk; the read happens on the lock screen, where it is on the path
     * of a user action anyway.
     */
    private fun restoreOnce() {
        if (restored) return
        restored = true
        val persisted = store.load()
        failedAttempts = persisted.failedAttempts
        // Clamped: a persisted deadline is wall-clock and therefore user-movable, so it is only ever
        // trusted for at most one back-off step. Winding the clock forward skips that step; it does
        // not touch failedAttempts, so the escalation resumes at full strength on the next failure.
        val remaining = (persisted.lockedUntilWallMs - wallClockMs())
            .coerceIn(0L, MAX_BACKOFF_STEPS * BACKOFF_UNIT_MS)
        lockedUntilElapsedMs = if (remaining > 0) nowMs() + remaining else 0L
    }

    private companion object {
        /** Wrong attempts allowed before the back-off kicks in. */
        const val FREE_ATTEMPTS = 5
        const val BACKOFF_UNIT_MS = 10_000L // 10s, 20s, 30s … per extra failure
        const val MAX_BACKOFF_STEPS = 6 // capped at 60s
    }
}
