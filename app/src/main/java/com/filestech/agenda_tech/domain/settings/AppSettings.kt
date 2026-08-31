package com.filestech.agenda_tech.domain.settings

import com.filestech.agenda_tech.domain.backup.AutoBackupOutcome
import com.filestech.agenda_tech.domain.model.CalendarColor
import timber.log.Timber

/** App theme preference. */
enum class ThemeMode(val rawValue: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2),
    ;

    companion object {
        fun fromRaw(rawValue: Int): ThemeMode = entries.firstOrNull { it.rawValue == rawValue }
            ?: SYSTEM.also { Timber.w("Unknown ThemeMode %d — defaulting to SYSTEM", rawValue) }
    }
}

/** First day of week preference; SYSTEM follows the device locale. */
enum class WeekStart(val rawValue: Int) {
    SYSTEM(0),
    MONDAY(1),
    SATURDAY(6),
    SUNDAY(7),
    ;

    companion object {
        fun fromRaw(rawValue: Int): WeekStart = entries.firstOrNull { it.rawValue == rawValue }
            ?: SYSTEM.also { Timber.w("Unknown WeekStart %d — defaulting to SYSTEM", rawValue) }
    }
}

/**
 * All user preferences, persisted via DataStore. Pure data — the wiring (theme, grids, editor
 * defaults, notifications, widget) reads this from [com.filestech.agenda_tech.domain.repository.SettingsRepository].
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val weekStart: WeekStart = WeekStart.SYSTEM,
    val showWeekNumbers: Boolean = false,
    val defaultEventColor: CalendarColor = CalendarColor.DEFAULT,
    val defaultDurationMinutes: Int = DEFAULT_DURATION_MINUTES,
    /** Minutes-before for a new event's reminder; -1 means no default reminder. */
    val defaultReminderMinutes: Int = NO_DEFAULT_REMINDER,
    val flagSecure: Boolean = true,
    /** SEC-W1 — when true the widget hides event titles (shows only times). */
    val widgetHideTitles: Boolean = false,
    /**
     * True once the user has answered the "restore a backup?" offer shown on an empty agenda —
     * whether they restored or waved it away. Without it the offer would come back on every launch
     * of a deliberately empty calendar, which is nagging, not helping.
     */
    val restorePromptDismissed: Boolean = false,
    /**
     * When the user last exported a backup **from this app**, or 0 if never.
     *
     * Deliberately named for what it can know. The app cannot tell whether a backup *exists*: the
     * file may have been deleted, or the agenda kept safe some other way. So nothing here ever claims
     * "you are protected" — only "this is the last time you exported".
     */
    val lastBackupAtUtcMillis: Long = 0L,
    /** Until when the backup reminder stays quiet after a "later". 0 = not snoozed. */
    val backupPromptSnoozedUntilUtcMillis: Long = 0L,
    /** Whether a backup is written on its own every week. Off until the user turns it on. */
    val autoBackupEnabled: Boolean = false,
    /**
     * The folder the user picked, as a SAF tree URI, or null if none.
     *
     * Held as the plain string the picker returned: the app has no storage permission and this URI
     * IS the whole of its access — a grant the user can revoke from the system settings at any time,
     * which is why every run re-checks it rather than trusting it.
     */
    val autoBackupFolderUri: String? = null,
    /** When the automatic backup last *ran*, whether or not it produced a file. 0 = never. */
    val autoBackupLastRunAtUtcMillis: Long = 0L,
    /** How that run ended — see [AutoBackupOutcome] for why a failure has to be visible. */
    val autoBackupLastOutcome: AutoBackupOutcome = AutoBackupOutcome.NEVER_RUN,
    val notifSound: Boolean = true,
    /**
     * Ringtone to play for reminders, as a content URI string. Null (the default) means the system
     * default notification sound. Ignored when [notifSound] is false.
     */
    val notifSoundUri: String? = null,
    val notifVibrate: Boolean = true,
    val notifLockScreen: Boolean = true,
) {
    companion object {
        const val DEFAULT_DURATION_MINUTES = 60
        const val NO_DEFAULT_REMINDER = -1
    }
}
