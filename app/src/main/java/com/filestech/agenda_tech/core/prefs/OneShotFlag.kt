package com.filestech.agenda_tech.core.prefs

import android.content.Context

/**
 * A boolean that survives process death: raised by whatever detects a problem, read **exactly once**
 * by whatever tells the user about it.
 *
 * ## Why this exists as a type
 *
 * `DatabaseFactory` already had this pattern, hand-rolled, for the one case it knew about: a Keystore
 * failure that forced the agenda to be wiped. Audit S15 found a second case with the same shape — a
 * corrupted settings file silently disabling the app lock — and a second hand-rolled copy is how the
 * two drift apart. The rule they share is the one that matters, and it is easy to get wrong: a
 * recovery the user is never told about is worse than the failure it recovers from, because the user
 * keeps trusting a protection that is no longer there.
 *
 * Deliberately backed by [android.content.SharedPreferences] and **not** by the DataStore: one of the
 * two flags exists precisely to report that the DataStore was lost, and a witness stored inside the
 * thing it witnesses is no witness at all. `commit()` rather than `apply()` on the raise side — the
 * process may be about to die, and the flag is the only trace left.
 */
@JvmInline
value class OneShotFlag(private val key: String) {

    /** Records that the problem happened. Idempotent; durable across a kill. */
    fun raise(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(key, true).commit()
    }

    /** True once per raise, then false — so the notice is shown exactly once. */
    fun consume(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(key, false)) return false
        prefs.edit().remove(key).apply()
        return true
    }

    companion object {
        internal const val PREFS = "agendatech_db"

        /** The agenda was wiped after an unrecoverable database-key failure. */
        val DATABASE_RESET = OneShotFlag("db_reset_pending")

        /**
         * The settings file was unreadable and was replaced with defaults — which, since the same
         * file carries `lock_enabled`, means **the app lock was switched off**.
         */
        val SETTINGS_RESET = OneShotFlag("settings_reset_pending")

        /**
         * A reboot pass could not re-arm every recurring reminder within its expansion allowance.
         *
         * Those reminders will not fire until their event is next saved. The only witness used to be
         * a `Timber.w`, which `NoOpReleaseTree` drops on the builds users run — so on release the app
         * knew its reminders were incomplete and said nothing. Both external reviews of that code
         * named it, one as a release blocker, and they were right: a silent functional loss is the
         * failure mode this whole audit exists to remove.
         */
        val REMINDERS_INCOMPLETE = OneShotFlag("reminders_incomplete")
    }
}
