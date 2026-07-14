package com.filestech.agenda_tech.security

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AppLockManagerTest {

    private fun managerAt(clock: () -> Long) = AppLockManager().apply { nowMs = clock }

    @Test
    fun `starts in UNKNOWN state`() {
        assertThat(AppLockManager().state.value).isEqualTo(LockState.UNKNOWN)
    }

    @Test
    fun `lock and unlock flip the state`() {
        val m = AppLockManager()
        m.lock()
        assertThat(m.state.value).isEqualTo(LockState.LOCKED)
        m.unlock()
        assertThat(m.state.value).isEqualTo(LockState.UNLOCKED)
    }

    @Test
    fun `no throttle before the free attempt budget is exhausted`() {
        var now = 0L
        val m = managerAt { now }
        repeat(4) { m.registerFailedAttempt() } // FREE_ATTEMPTS = 5
        assertThat(m.throttleRemainingMs()).isEqualTo(0L)
    }

    @Test
    fun `throttle kicks in after the fifth failed attempt`() {
        var now = 0L
        val m = managerAt { now }
        repeat(5) { m.registerFailedAttempt() }
        assertThat(m.throttleRemainingMs()).isEqualTo(10_000L) // first back-off step
    }

    @Test
    fun `throttle escalates with further failures`() {
        var now = 0L
        val m = managerAt { now }
        repeat(6) { m.registerFailedAttempt() }
        assertThat(m.throttleRemainingMs()).isEqualTo(20_000L) // second step
    }

    @Test
    fun `throttle counts down as the clock advances`() {
        var now = 0L
        val m = managerAt { now }
        repeat(5) { m.registerFailedAttempt() }
        now = 7_000L
        assertThat(m.throttleRemainingMs()).isEqualTo(3_000L)
        now = 10_000L
        assertThat(m.throttleRemainingMs()).isEqualTo(0L)
    }

    @Test
    fun `resetAttempts clears the throttle`() {
        var now = 0L
        val m = managerAt { now }
        repeat(6) { m.registerFailedAttempt() }
        m.resetAttempts()
        assertThat(m.throttleRemainingMs()).isEqualTo(0L)
    }
}
