package com.filestech.agenda_tech.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.domain.repository.SettingsRepository
import com.filestech.agenda_tech.domain.settings.AppSettings
import com.filestech.agenda_tech.domain.settings.ThemeMode
import com.filestech.agenda_tech.domain.settings.WeekStart
import com.filestech.agenda_tech.system.notifications.ReminderNotifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val reminderNotifier: ReminderNotifier,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = AppSettings(),
    )

    fun setThemeMode(value: ThemeMode) = update { it.copy(themeMode = value) }
    fun setWeekStart(value: WeekStart) = update { it.copy(weekStart = value) }
    fun setShowWeekNumbers(value: Boolean) = update { it.copy(showWeekNumbers = value) }
    fun setDefaultColor(value: CalendarColor) = update { it.copy(defaultEventColor = value) }
    fun setDefaultDuration(minutes: Int) = update { it.copy(defaultDurationMinutes = minutes) }
    fun setDefaultReminder(minutes: Int) = update { it.copy(defaultReminderMinutes = minutes) }
    fun setFlagSecure(value: Boolean) = update { it.copy(flagSecure = value) }
    fun setWidgetHideTitles(value: Boolean) = update { it.copy(widgetHideTitles = value) }
    fun setNotifSound(value: Boolean) = updateThenRebuildChannel { it.copy(notifSound = value) }
    fun setNotifVibrate(value: Boolean) = updateThenRebuildChannel { it.copy(notifVibrate = value) }
    fun setNotifLockScreen(value: Boolean) = updateThenRebuildChannel { it.copy(notifLockScreen = value) }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    /** Notification-channel prefs only take effect once the channel is recreated (Android caps it). */
    private fun updateThenRebuildChannel(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            settingsRepository.update(transform)
            reminderNotifier.rebuildChannel()
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
