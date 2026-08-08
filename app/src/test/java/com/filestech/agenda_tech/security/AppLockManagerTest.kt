package com.filestech.agenda_tech.security

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
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

    // --- attemptPin: one call, one guess (audit S16) -------------------------

    @Test
    fun `attemptPin spends a guess and reports the outcome`() = runTest {
        val m = manager()
        assertThat(m.attemptPin { false }).isInstanceOf(AppLockManager.Attempt.Rejected::class.java)
        assertThat(m.attemptPin { true }).isEqualTo(AppLockManager.Attempt.Accepted)
        // A correct PIN clears the escalation, exactly as resetAttempts did in the old flow.
        assertThat(m.throttleRemainingMs()).isEqualTo(0L)
    }

    @Test
    fun `attemptPin refuses without spending a guess while the back-off runs`() = runTest {
        val m = manager()
        repeat(5) { m.attemptPin { false } } // FREE_ATTEMPTS = 5 → 10s back-off

        val throttled = m.attemptPin { error("verification must not even be attempted") }

        assertThat(throttled).isEqualTo(AppLockManager.Attempt.Throttled(10_000L))
        // The refused call must NOT count as a failure: charging it would let a locked-out user
        // escalate their own back-off by tapping, which is not what the escalation is for.
        assertThat(m.throttleRemainingMs()).isEqualTo(10_000L)
    }

    @Test
    fun `a burst of attempts cannot outrun the back-off it triggers`() = runTest {
        // THE point of S16, and the assertion had to be built to tell the two versions apart.
        //
        // Starting from a back-off that is ALREADY running proves nothing: every attempt is refused
        // either way. The discriminating state is one guess short of the threshold — that is where the
        // old flow lost. It read the back-off, released the lock, spent ~100 ms in PBKDF2 and only
        // then recorded the failure, so ten taps landing in that gap all read zero and all got a
        // guess. The back-off they triggered applied to none of them.
        val m = manager()
        repeat(4) { m.attemptPin { false } } // FREE_ATTEMPTS = 5, so the next failure arms the back-off

        val gate = CompletableDeferred<Unit>()
        val verified = mutableListOf<Int>()
        val results = List(10) { index ->
            async {
                m.attemptPin {
                    verified += index
                    gate.await() // suspends exactly where the old code did NOT hold the mutex
                    false
                }
            }
        }
        runCurrent()
        gate.complete(Unit)
        val outcomes = results.awaitAll()

        // Exactly one guess was spent; the other nine met the back-off the first one armed.
        assertThat(verified).hasSize(1)
        assertThat(outcomes.count { it is AppLockManager.Attempt.Rejected }).isEqualTo(1)
        assertThat(outcomes.count { it is AppLockManager.Attempt.Throttled }).isEqualTo(9)
        assertThat(m.throttleRemainingMs()).isEqualTo(10_000L) // first step, not the tenth
    }

    @Test
    fun `the verifier runs under the lock, so a second attempt waits for the first`() = runTest {
        val m = manager()
        val firstEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var secondEnteredWhileFirstHeld = false

        val first = async {
            m.attemptPin {
                firstEntered.complete(Unit)
                release.await()
                false
            }
        }
        firstEntered.await()
        val second = async { m.attemptPin { secondEnteredWhileFirstHeld = !release.isCompleted; false } }
        runCurrent()

        // While the first verification is still running, the second has not started its own.
        assertThat(secondEnteredWhileFirstHeld).isFalse()
        release.complete(Unit)
        first.await()
        second.await()
        // Two guesses spent, two counted — the counter and the decision agree, which is the whole
        // property the class KDoc claimed and did not have.
        assertThat(m.attemptPin { false }).isInstanceOf(AppLockManager.Attempt.Rejected::class.java)
    }
}
