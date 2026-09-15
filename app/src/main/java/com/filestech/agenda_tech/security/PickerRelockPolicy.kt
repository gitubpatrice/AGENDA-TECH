package com.filestech.agenda_tech.security

import android.os.SystemClock

/**
 * Whether leaving the foreground for an activity **the app itself opened** — a file picker, the
 * ringtone picker, a permission dialog — re-locks the app.
 *
 * ## Why
 *
 * `MainActivity.onStop` re-locks whenever the app leaves the foreground. A system file picker is a
 * separate activity, so opening one stopped `MainActivity` and locked it: the user typed their PIN in
 * the middle of an export or a restore they had just started. Worse, the lock screen replaced the
 * whole UI while it was shown, so the screen that was waiting for the picker's result was gone — a
 * restore stopped silently after the PIN, back on the month view (reported on a Galaxy S9,
 * 2026-09-15). Patrice's rule: **as long as the user is inside the app, no PIN again.**
 *
 * ## The three bounds, and why each exists
 *
 * - [LAUNCH_WINDOW_MS]: only a stop that follows the launch within this window is spared. A launch
 *   that never stopped the activity (a translucent permission dialog, a picker that failed to open)
 *   must not leave a pass that a later press on Home would use. [onResumed] expires it as well.
 * - [onScreenOff]: the screen turning off while the picker is open locks at once — a phone put down.
 * - [RETURN_GRACE_MS]: a user who comes back from the picker later than this is locked anyway.
 *
 * Known limit, accepted: someone who leaves the picker for Home and hands the phone over with the
 * screen still on gets the agenda unlocked if they return within [RETURN_GRACE_MS].
 *
 * Going to another app, pressing Home or opening a notification is unchanged: it locks.
 *
 * Pure apart from the default clock, so every decision is tested on the JVM.
 */
class PickerRelockPolicy(private val now: () -> Long = { SystemClock.elapsedRealtime() }) {

    private var launchedAt: Long? = null
    private var stoppedAt: Long? = null

    /** The app is about to open an activity for a result. */
    fun onExternalActivityLaunched() {
        launchedAt = now()
        stoppedAt = null
    }

    /**
     * `onStop`: true when this stop is the one that launch caused, so the lock is spared.
     * Consumes the launch — a second stop is never spared by the same one.
     */
    fun sparesLockOnStop(): Boolean {
        val launched = launchedAt ?: return false
        launchedAt = null
        val stopped = now()
        if (stopped - launched > LAUNCH_WINDOW_MS) return false
        stoppedAt = stopped
        return true
    }

    /** `onStart`: true when the user is back from a spared stop too late, and the app must lock now. */
    fun locksOnReturn(): Boolean {
        val stopped = stoppedAt ?: return false
        stoppedAt = null
        return now() - stopped > RETURN_GRACE_MS
    }

    /** True while a spared stop is pending: `MainActivity` listens for the screen turning off only then. */
    val isSparingLock: Boolean
        get() = stoppedAt != null

    /**
     * The screen turned off: true when a spared stop is pending, and the app must lock now.
     * Consumes the stop.
     *
     * Chosen by Patrice on 2026-09-15 as the second bound. Once the picker is on screen the app sees
     * nothing more — it cannot tell "picked a file" from "pressed Home and came back through Recents".
     * A phone put down with a picker open turns its screen off, and that is the case this closes; the
     * [RETURN_GRACE_MS] window only remains for someone who leaves the picker and hands the phone over
     * with the screen still on.
     */
    fun onScreenOff(): Boolean {
        if (stoppedAt == null) return false
        stoppedAt = null
        return true
    }

    /** `onResume`: a launch that did not stop the activity expires here. */
    fun onResumed() {
        launchedAt = null
    }

    companion object {
        const val LAUNCH_WINDOW_MS = 3_000L
        const val RETURN_GRACE_MS = 3 * 60_000L
    }
}
