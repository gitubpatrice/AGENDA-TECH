package com.filestech.agenda_tech.security

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * When opening a picker spares the app its re-lock — and, just as important, when it does not.
 *
 * Every "does not" below is a way the exemption could leak into an ordinary trip out of the app, which
 * would turn "no PIN inside the app" into "no PIN at all".
 */
class PickerRelockPolicyTest {

    private var clock = 1_000_000L
    private val policy = PickerRelockPolicy { clock }

    @Test
    fun `a picker that stops the app right after its launch spares the lock`() {
        policy.onExternalActivityLaunched()
        clock += 400
        assertThat(policy.sparesLockOnStop()).isTrue()
    }

    @Test
    fun `coming back from the picker in time does not lock`() {
        policy.onExternalActivityLaunched()
        clock += 400
        policy.sparesLockOnStop()
        clock += 60_000
        assertThat(policy.locksOnReturn()).isFalse()
    }

    @Test
    fun `coming back from the picker too late locks`() {
        policy.onExternalActivityLaunched()
        clock += 400
        policy.sparesLockOnStop()
        clock += PickerRelockPolicy.RETURN_GRACE_MS + 1
        assertThat(policy.locksOnReturn()).isTrue()
    }

    @Test
    fun `an ordinary stop with no picker is never spared`() {
        assertThat(policy.sparesLockOnStop()).isFalse()
        assertThat(policy.locksOnReturn()).isFalse()
    }

    @Test
    fun `a stop long after the launch is not the picker's and is not spared`() {
        // A launch that never stopped the activity, then the user presses Home a minute later.
        policy.onExternalActivityLaunched()
        clock += PickerRelockPolicy.LAUNCH_WINDOW_MS + 1
        assertThat(policy.sparesLockOnStop()).isFalse()
    }

    @Test
    fun `one launch spares one stop, not the next one`() {
        policy.onExternalActivityLaunched()
        clock += 400
        assertThat(policy.sparesLockOnStop()).isTrue()
        clock += 1_000
        policy.locksOnReturn()
        clock += 1_000
        assertThat(policy.sparesLockOnStop()).isFalse()
    }

    @Test
    fun `the screen turning off while a picker is open locks`() {
        policy.onExternalActivityLaunched()
        clock += 400
        policy.sparesLockOnStop()
        assertThat(policy.isSparingLock).isTrue()
        clock += 20_000
        assertThat(policy.onScreenOff()).isTrue()
        // Consumed: coming back afterwards does not lock a second time, and nothing is still spared.
        assertThat(policy.isSparingLock).isFalse()
        assertThat(policy.locksOnReturn()).isFalse()
    }

    @Test
    fun `the screen turning off with no picker open does nothing`() {
        assertThat(policy.isSparingLock).isFalse()
        assertThat(policy.onScreenOff()).isFalse()
    }

    @Test
    fun `a launch that only paused the app expires on resume`() {
        // A translucent permission dialog pauses without stopping; the pass must not survive it.
        policy.onExternalActivityLaunched()
        clock += 800
        policy.onResumed()
        clock += 200
        assertThat(policy.sparesLockOnStop()).isFalse()
    }
}
