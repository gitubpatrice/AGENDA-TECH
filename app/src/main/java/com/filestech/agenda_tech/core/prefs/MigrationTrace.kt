package com.filestech.agenda_tech.core.prefs

import android.content.Context

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
 * This is diagnostic only — nothing reads it in the UI. It is meant to be pulled off a device:
 *
 * ```
 * adb shell run-as com.filestech.agenda_tech.debug \
 *   cat shared_prefs/agendatech_db.xml
 * ```
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

    /** Appends [note], oldest entries first, keeping the record under [MAX_CHARS]. */
    fun record(context: Context, note: String) {
        val prefs = context.getSharedPreferences(OneShotFlag.PREFS, Context.MODE_PRIVATE)
        val merged = (prefs.getString(KEY, null)?.plus("\n") ?: "") + note
        // Keeps the TAIL: the most recent migration is the one being diagnosed, and an old entry that
        // has to be dropped is one whose migration already shipped and was investigated or forgotten.
        prefs.edit()
            .putString(KEY, merged.takeLast(MAX_CHARS))
            .commit()
    }

    /** Everything recorded so far, or null. Read by tests and by hand; nothing in the UI uses it. */
    fun read(context: Context): String? =
        context.getSharedPreferences(OneShotFlag.PREFS, Context.MODE_PRIVATE).getString(KEY, null)
}
