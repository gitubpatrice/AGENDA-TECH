package com.filestech.agenda_tech.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.domain.repository.SettingsRepository
import com.filestech.agenda_tech.domain.settings.AppSettings
import com.filestech.agenda_tech.domain.settings.ThemeMode
import com.filestech.agenda_tech.domain.settings.WeekStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data.map { it.toAppSettings() }

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            prefs.writeAppSettings(transform(prefs.toAppSettings()))
        }
    }

    private fun Preferences.toAppSettings(): AppSettings = AppSettings(
        themeMode = ThemeMode.fromRaw(this[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.rawValue),
        weekStart = WeekStart.fromRaw(this[Keys.WEEK_START] ?: WeekStart.SYSTEM.rawValue),
        showWeekNumbers = this[Keys.SHOW_WEEK_NUMBERS] ?: false,
        defaultEventColor = CalendarColor.fromRaw(this[Keys.DEFAULT_COLOR] ?: CalendarColor.DEFAULT.rawValue),
        defaultDurationMinutes = this[Keys.DEFAULT_DURATION] ?: AppSettings.DEFAULT_DURATION_MINUTES,
        defaultReminderMinutes = this[Keys.DEFAULT_REMINDER] ?: AppSettings.NO_DEFAULT_REMINDER,
        flagSecure = this[Keys.FLAG_SECURE] ?: true,
        widgetHideTitles = this[Keys.WIDGET_HIDE_TITLES] ?: false,
        restorePromptDismissed = this[Keys.RESTORE_PROMPT_DISMISSED] ?: false,
        lastBackupAtUtcMillis = this[Keys.LAST_BACKUP_AT] ?: 0L,
        backupPromptSnoozedUntilUtcMillis = this[Keys.BACKUP_PROMPT_SNOOZED_UNTIL] ?: 0L,
        notifSound = this[Keys.NOTIF_SOUND] ?: true,
        notifSoundUri = this[Keys.NOTIF_SOUND_URI],
        notifVibrate = this[Keys.NOTIF_VIBRATE] ?: true,
        notifLockScreen = this[Keys.NOTIF_LOCKSCREEN] ?: true,
    )

    private fun MutablePreferences.writeAppSettings(settings: AppSettings) {
        this[Keys.THEME_MODE] = settings.themeMode.rawValue
        this[Keys.WEEK_START] = settings.weekStart.rawValue
        this[Keys.SHOW_WEEK_NUMBERS] = settings.showWeekNumbers
        this[Keys.DEFAULT_COLOR] = settings.defaultEventColor.rawValue
        this[Keys.DEFAULT_DURATION] = settings.defaultDurationMinutes
        this[Keys.DEFAULT_REMINDER] = settings.defaultReminderMinutes
        this[Keys.FLAG_SECURE] = settings.flagSecure
        this[Keys.WIDGET_HIDE_TITLES] = settings.widgetHideTitles
        this[Keys.RESTORE_PROMPT_DISMISSED] = settings.restorePromptDismissed
        this[Keys.LAST_BACKUP_AT] = settings.lastBackupAtUtcMillis
        this[Keys.BACKUP_PROMPT_SNOOZED_UNTIL] = settings.backupPromptSnoozedUntilUtcMillis
        this[Keys.NOTIF_SOUND] = settings.notifSound
        // Absent key = system default ringtone, so clear it rather than storing a sentinel.
        settings.notifSoundUri
            ?.let { this[Keys.NOTIF_SOUND_URI] = it }
            ?: remove(Keys.NOTIF_SOUND_URI)
        this[Keys.NOTIF_VIBRATE] = settings.notifVibrate
        this[Keys.NOTIF_LOCKSCREEN] = settings.notifLockScreen
    }

    private object Keys {
        val THEME_MODE = intPreferencesKey("theme_mode")
        val WEEK_START = intPreferencesKey("week_start")
        val SHOW_WEEK_NUMBERS = booleanPreferencesKey("show_week_numbers")
        val DEFAULT_COLOR = intPreferencesKey("default_color")
        val DEFAULT_DURATION = intPreferencesKey("default_duration")
        val DEFAULT_REMINDER = intPreferencesKey("default_reminder")
        val FLAG_SECURE = booleanPreferencesKey("flag_secure")
        val WIDGET_HIDE_TITLES = booleanPreferencesKey("widget_hide_titles")
        val RESTORE_PROMPT_DISMISSED = booleanPreferencesKey("restore_prompt_dismissed")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        val BACKUP_PROMPT_SNOOZED_UNTIL = longPreferencesKey("backup_prompt_snoozed_until")
        val NOTIF_SOUND = booleanPreferencesKey("notif_sound")
        val NOTIF_SOUND_URI = stringPreferencesKey("notif_sound_uri")
        val NOTIF_VIBRATE = booleanPreferencesKey("notif_vibrate")
        val NOTIF_LOCKSCREEN = booleanPreferencesKey("notif_lockscreen")
    }
}
