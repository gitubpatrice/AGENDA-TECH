package com.filestech.agenda_tech.domain.backup

import timber.log.Timber

/**
 * How the last automatic backup ended, persisted so the Backup screen can say something true rather
 * than a reassuring nothing.
 *
 * A typed value, not a message: the run happens in a Worker with no locale of its own, and the text
 * belongs to the screen that shows it — the same split the editor uses for its validation errors.
 *
 * The distinction that matters to the user is between *"nothing was written and you should act"*
 * ([NO_FOLDER], [NO_PASSWORD], [FOLDER_UNAVAILABLE] — typically the folder grant was revoked, or the
 * SD card is out) and *"something broke inside the app"* ([EXPORT_FAILED], [WRITE_FAILED]).
 */
enum class AutoBackupOutcome(val rawValue: Int) {
    NEVER_RUN(0),
    OK(1),
    NO_FOLDER(2),
    NO_PASSWORD(3),
    FOLDER_UNAVAILABLE(4),
    EXPORT_FAILED(5),
    WRITE_FAILED(6),
    ;

    val isFailure: Boolean get() = this != NEVER_RUN && this != OK

    companion object {
        fun fromRaw(rawValue: Int): AutoBackupOutcome = entries.firstOrNull { it.rawValue == rawValue }
            ?: NEVER_RUN.also { Timber.w("Unknown AutoBackupOutcome %d — defaulting to NEVER_RUN", rawValue) }
    }
}
