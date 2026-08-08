package com.filestech.agenda_tech.domain

/**
 * The one answer this app gives to "how many events is too many to take in at once".
 *
 * ## Why a shared ceiling (audit S12)
 *
 * Every import ceiling in the app was a **byte** ceiling: 5 MiB for a `.ics`, 16 MiB for an `.atbak`.
 * Bytes bound memory, which is what those caps were for, but they say nothing about the number of
 * rows that lands in the database — and rows are what the calendar then has to render, expand and arm
 * alarms for. Five MiB of minimal `BEGIN:VEVENT` blocks is roughly 87 000 events; a 16 MiB `.atbak`
 * carries reminders too, and `BackupViewModel` re-arms an alarm for each of them. Neither crashes, but
 * the agenda comes out unusable and only another restore undoes it.
 *
 * The device-calendar import already had a ceiling of 20 000 and kept it to itself. One number,
 * defined once, is what stops the three paths from each having their own idea.
 *
 * The value is deliberately far above any personal agenda — twenty thousand events is a full
 * appointment every working hour for about ten years — so a real user cannot meet it. That is the
 * point: a ceiling a legitimate file can reach is a ceiling that refuses legitimate files, which is
 * how audit F4 got its third symptom.
 *
 * ## Why the three paths react differently
 *
 * Not an inconsistency; the inputs are not the same thing.
 *
 * - A **file the user picked** (`.ics`, `.atbak`) is refused **whole**, with a message. Importing part
 *   of it looks exactly like importing all of it, and the user can act on a refusal — split the file,
 *   or pick another. `RestoreBackupUseCase` already refuses whole for every other reason.
 * - The **device calendar** is a live provider with no file to hand back. It is truncated, as it
 *   always was, but the truncation is now logged instead of silent.
 */
object ImportLimits {

    /** Maximum events accepted from a single import, whatever the source. */
    const val MAX_EVENTS = 20_000
}
