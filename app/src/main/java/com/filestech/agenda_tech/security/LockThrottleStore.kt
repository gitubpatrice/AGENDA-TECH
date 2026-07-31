package com.filestech.agenda_tech.security

/**
 * The PIN back-off state, as it survives process death.
 *
 * [lockedUntilWallMs] is wall-clock (`System.currentTimeMillis`) rather than the monotonic clock the
 * live back-off runs on: a monotonic deadline is meaningless once the process — or the device — has
 * restarted. Wall-clock is trivially movable by the user, which is why [AppLockManager] clamps
 * whatever it reads back to one maximum back-off step. Winding the clock forward therefore buys an
 * attacker at most that one step, never a reset of the attempt counter.
 */
data class LockThrottle(
    val failedAttempts: Int,
    val lockedUntilWallMs: Long,
) {
    companion object {
        val NONE = LockThrottle(failedAttempts = 0, lockedUntilWallMs = 0L)
    }
}

/**
 * Persists the PIN brute-force back-off (audit SEC-2).
 *
 * Keeping this behind an interface keeps [AppLockManager] a pure JVM class — its back-off logic is
 * unit-tested against a fake store, with no Android framework and no Robolectric.
 */
interface LockThrottleStore {

    /** Reads the persisted state; [LockThrottle.NONE] when nothing was ever stored. */
    fun load(): LockThrottle

    /**
     * Writes the state **durably before returning**. The whole point of persisting is to survive a
     * `force-stop` issued moments after a wrong PIN, so this cannot be a deferred write.
     */
    fun save(throttle: LockThrottle)
}
