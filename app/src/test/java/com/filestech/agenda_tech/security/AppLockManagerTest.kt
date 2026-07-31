package com.filestech.agenda_tech.security

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AppLockManagerTest {

    /** Stands in for the on-disk store; surviving a "restart" is just reusing the same instance. */
    private class FakeThrottleStore(private var value: LockThrottle = LockThrottle.NONE) : LockThrottleStore {
        override fun load(): LockThrottle = value
        override fun save(throttle: LockThrottle) {
            value = throttle
        }
    }

    private fun manager(
        store: LockThrottleStore = FakeThrottleStore(),
        clock: () -> Long = { 0L },
        wall: () -> Long = { 0L },
    ) = AppLockManager(store, Dispatchers.Unconfined).apply {
        nowMs = clock
        wallClockMs = wall
    }

    @Test
    fun `starts in UNKNOWN state`() {
        assertThat(manager().state.value).isEqualTo(LockState.UNKNOWN)
    }

    @Test
    fun `lock and unlock flip the state`() {
        val m = manager()
        m.lock()
        assertThat(m.state.value).isEqualTo(LockState.LOCKED)
        m.unlock()
        assertThat(m.state.value).isEqualTo(LockState.UNLOCKED)
    }

    @Test
    fun `no throttle before the free attempt budget is exhausted`() = runTest {
        val m = manager()
        repeat(4) { m.registerFailedAttempt() } // FREE_ATTEMPTS = 5
        assertThat(m.throttleRemainingMs()).isEqualTo(0L)
    }

    @Test
    fun `throttle kicks in after the fifth failed attempt`() = runTest {
        val m = manager()
        repeat(5) { m.registerFailedAttempt() }
        assertThat(m.throttleRemainingMs()).isEqualTo(10_000L) // first back-off step
    }

    @Test
    fun `throttle escalates with further failures`() = runTest {
        val m = manager()
        repeat(6) { m.registerFailedAttempt() }
        assertThat(m.throttleRemainingMs()).isEqualTo(20_000L) // second step
    }

    @Test
    fun `throttle counts down as the clock advances`() = runTest {
        var now = 0L
        val m = manager(clock = { now })
        repeat(5) { m.registerFailedAttempt() }
        now = 7_000L
        assertThat(m.throttleRemainingMs()).isEqualTo(3_000L)
        now = 10_000L
        assertThat(m.throttleRemainingMs()).isEqualTo(0L)
    }

    @Test
    fun `resetAttempts clears the throttle`() = runTest {
        val m = manager()
        repeat(6) { m.registerFailedAttempt() }
        m.resetAttempts()
        assertThat(m.throttleRemainingMs()).isEqualTo(0L)
    }

    // --- Audit SEC-2: the back-off has to survive process death ---

    @Test
    fun `a process restart no longer resets the attempt counter`() = runTest {
        val store = FakeThrottleStore()
        var wall = 1_000_000L
        val before = manager(store, clock = { 0L }, wall = { wall })
        repeat(5) { before.registerFailedAttempt() }
        assertThat(before.throttleRemainingMs()).isEqualTo(10_000L)

        // force-stop, then relaunch once the persisted back-off has elapsed. The attacker gets their
        // attempt — but as the 6th failure overall, not as a fresh first one.
        wall += 10_000L
        val after = manager(store, clock = { 0L }, wall = { wall })
        assertThat(after.throttleRemainingMs()).isEqualTo(0L)
        after.registerFailedAttempt()
        assertThat(after.throttleRemainingMs()).isEqualTo(20_000L)
    }

    @Test
    fun `a back-off still in effect survives a process restart`() = runTest {
        val store = FakeThrottleStore()
        var wall = 1_000_000L
        val before = manager(store, clock = { 0L }, wall = { wall })
        repeat(5) { before.registerFailedAttempt() }

        wall += 4_000L // restarted 4s into the 10s back-off
        val after = manager(store, clock = { 0L }, wall = { wall })
        assertThat(after.throttleRemainingMs()).isEqualTo(6_000L)
    }

    @Test
    fun `a persisted deadline is clamped to a single maximum back-off`() = runTest {
        // Winding the wall clock back (or a tampered store) must not buy an unbounded lock-out.
        val store = FakeThrottleStore(LockThrottle(failedAttempts = 9, lockedUntilWallMs = Long.MAX_VALUE))
        val m = manager(store, clock = { 0L }, wall = { 0L })
        assertThat(m.throttleRemainingMs()).isEqualTo(60_000L) // MAX_BACKOFF_STEPS × BACKOFF_UNIT_MS
    }

    @Test
    fun `winding the clock forward skips the wait but not the escalation`() = runTest {
        val store = FakeThrottleStore()
        var wall = 1_000_000L
        val before = manager(store, clock = { 0L }, wall = { wall })
        repeat(6) { before.registerFailedAttempt() } // 20s back-off pending

        wall += 10_000_000L // attacker jumps the clock past the deadline
        val after = manager(store, clock = { 0L }, wall = { wall })
        assertThat(after.throttleRemainingMs()).isEqualTo(0L)
        after.registerFailedAttempt()
        assertThat(after.throttleRemainingMs()).isEqualTo(30_000L) // 7th failure, third step
    }

    @Test
    fun `resetAttempts clears the persisted state`() = runTest {
        val store = FakeThrottleStore()
        val m = manager(store)
        repeat(6) { m.registerFailedAttempt() }
        m.resetAttempts()
        assertThat(store.load()).isEqualTo(LockThrottle.NONE)
    }
}
