package com.filestech.agenda_tech.domain.settings

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
    val notifSound: Boolean = true,
    val notifVibrate: Boolean = true,
    val notifLockScreen: Boolean = true,
) {
    companion object {
        const val DEFAULT_DURATION_MINUTES = 60
        const val NO_DEFAULT_REMINDER = -1
    }
}
