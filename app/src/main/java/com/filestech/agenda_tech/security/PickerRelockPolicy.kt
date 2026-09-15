package com.filestech.agenda_tech.security

import android.os.SystemClock

/**
 * Whether leaving the foreground for an activity **the app itself opened** — a file picker, the
 * ringtone picker — re-locks the app.
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
 * ## The bounds, and why each exists
 *
 * - [LAUNCH_WINDOW_MS]: only a stop that follows the launch within this window is spared. A launch
 *   that never stopped the activity (a picker that failed to open) must not leave a pass that a later
 *   press on Home would use. [onResumed] expires it as well.
 * - [onScreenOff]: the screen turning off while the picker is open locks at once — a phone put down.
 * - [onExternalIntent]: coming back through a notification, the widget or the launcher icon is
 *   entering the app from outside, and locks — found by the pre-tag audit of v1.1.1.
 * - [RETURN_GRACE_MS]: a user who comes back from the picker later than this is locked anyway.
 *
 * Known limit, accepted: someone who leaves the picker for Home and returns **through Recents** with
 * the screen still on gets the agenda unlocked within [RETURN_GRACE_MS]. Recents delivers no intent,
 * so nothing distinguishes that return from the picker handing back its result.
 *
 * Pure apart from the default clock, so every decision is tested on the JVM.
 */
class PickerRelockPolicy(private val now: () -> Long = { SystemClock.elapsedRealtime() }) {

    private var launchedAt: Long? = null
    private var stoppedAt: Long? = null

    /**
     * Set by [locksOnReturn] when the user came back in time, cleared by [onResumed]. `onStart` and
     * `onNewIntent` are not ordered by the platform for a `singleTask` activity brought back from the
     * stopped state, so an external intent must still be able to lock between the two.
     */
    private var returnedInTime = false

    /** The app is about to open an activity for a result. */
    fun onExternalActivityLaunched() {
        launchedAt = now()
        stoppedAt = null
        returnedInTime = false
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
        if (now() - stopped > RETURN_GRACE_MS) return true
        returnedInTime = true
        return false
    }

    /** True while a spared stop is pending: `MainActivity` listens for the screen turning off only then. */
    val isSparingLock: Boolean
        get() = stoppedAt != null

    /**
     * The screen turned off: true when a spared stop is pending, and the app must lock now.
     * Consumes the stop.
     *
     * Chosen by Patrice on 2026-09-15. Once the picker is on screen the app sees nothing more — it
     * cannot tell "picked a file" from "pressed Home and came back through Recents". A phone put down
     * with a picker open turns its screen off, and that is the case this closes.
     */
    fun onScreenOff(): Boolean {
        if (stoppedAt == null) return false
        stoppedAt = null
        return true
    }

    /**
     * `onNewIntent`: the app was brought back by an intent — a reminder notification, the widget, the
     * launcher icon — instead of by the picker handing back its result. True when that happened while
     * a spared stop was pending or before the return completed, and the app must lock now.
     *
     * Found by the pre-tag audit of v1.1.1: `MainActivity` is `singleTask`, so such an intent destroys
     * the picker and resumes the activity without another `onStop`, and only the 3-minute bound was
     * checked. `SECURITY.md` said opening a notification always locks; this makes it true.
     */
    fun onExternalIntent(): Boolean {
        val pending = stoppedAt != null || returnedInTime
        stoppedAt = null
        returnedInTime = false
        return pending
    }

    /** `onResume`: a launch that did not stop the activity expires here, and the return is complete. */
    fun onResumed() {
        launchedAt = null
        returnedInTime = false
    }

    companion object {
        const val LAUNCH_WINDOW_MS = 3_000L
        const val RETURN_GRACE_MS = 3 * 60_000L
    }
}
