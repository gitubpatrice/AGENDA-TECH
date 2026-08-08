package com.filestech.agenda_tech.core.prefs

import android.content.Context
import com.filestech.agenda_tech.BuildConfig

/**
 * A durable, human-readable note about what a data-repairing migration changed.
 *
 * ## Why (audit D3)
 *
 * The v6 migration rewrites `events.time_zone` and, in doing so, **destroys the only record of what
 * the file originally said**. Its KDoc justified a `Timber.i` as "the only way to tell a repaired
 * Windows name from a value that fell back" — but `NoOpReleaseTree` drops everything in release, and
 * its `log` is an empty method. So on the builds users actually run, that witness did not exist, on
 * the one migration in the repository that cannot be undone.
 *
 * A user reporting "since the update my Outlook meetings are in the wrong zone" could not be
 * diagnosed: the old value was gone and nothing said how many rows had fallen back to the device zone
 * for want of a better answer.
 *
 * ## Why it is a DEBUG-only witness (audit DR-3 / SEC-3, then the crypto review)
 *
 * An earlier KDoc prescribed `adb shell run-as … cat shared_prefs/…`. `run-as` requires
 * `android:debuggable`, so the procedure only ever worked on the build where `Timber` already
 * worked — the one build that did **not** have the problem D3 set out to fix. That was corrected to
 * a modest claim: a reproduction aid, "reproduce the migration on a debug build with the reporter's
 * `.atbak`".
 *
 * An external review then took that claim apart, and it does not survive:
 *
 * - A `.atbak` exported **after** the update carries the **repaired** `time_zone` values. Replaying
 *   the migration on it observes nothing, because the destruction already happened upstream.
 * - A `.atbak` exported **before** the update carries the original values *directly* — so in the
 *   only case where the answer is still recoverable, the trace is redundant.
 *
 * The release-build write was therefore paying a real price for nothing: zone names, which are
 * metadata derived from the user's agenda, written in **cleartext SharedPreferences outside the
 * SQLCipher container**, to be read by nobody. [record] is now a no-op outside debug builds. Debug
 * keeps it, because that is where it is legible and where `MigrationsTest` asserts on it.
 *
 * Exposing it on release through the About screen stays a choice **not** made: it would put that
 * same cleartext metadata behind a button instead of removing it.
 *
 * ## What may go in it
 *
 * Time-zone names and counts. **Never** a title, a location or a note — a zone name comes from the
 * calendar file's metadata, not from the user's agenda content, which is the line `NoOpReleaseTree`
 * draws. Bounded in length so a pathological import cannot grow the preferences file without limit.
 */
object MigrationTrace {

    private const val KEY = "migration_trace"
    private const val MAX_CHARS = 2_000

    /**
     * Appends [note], oldest entries first, keeping the record under [MAX_CHARS].
     *
     * **Does nothing outside a debug build** — see the class KDoc for why the release write bought
     * no diagnosis and cost cleartext metadata.
     */
    fun record(context: Context, note: String) {
        if (!BuildConfig.DEBUG) return
        val prefs = context.getSharedPreferences(OneShotFlag.PREFS, Context.MODE_PRIVATE)
        val merged = (prefs.getString(KEY, null)?.plus("\n") ?: "") + note
        // Keeps the TAIL: the most recent migration is the one being diagnosed, and an old entry that
        // has to be dropped is one whose migration already shipped and was investigated or forgotten.
        prefs.edit()
            .putString(KEY, merged.takeLast(MAX_CHARS))
            .commit()
    }

    /**
     * Everything recorded so far, or null — which is what a release build always returns, since
     * [record] writes nothing there. Read by `MigrationsTest`; nothing in the UI uses it.
     */
    fun read(context: Context): String? =
        context.getSharedPreferences(OneShotFlag.PREFS, Context.MODE_PRIVATE).getString(KEY, null)
}
