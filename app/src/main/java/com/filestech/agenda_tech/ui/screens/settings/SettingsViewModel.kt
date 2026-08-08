package com.filestech.agenda_tech.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.domain.repository.LockRepository
import com.filestech.agenda_tech.domain.repository.SettingsRepository
import com.filestech.agenda_tech.domain.settings.AppSettings
import com.filestech.agenda_tech.domain.settings.ThemeMode
import com.filestech.agenda_tech.domain.settings.WeekStart
import com.filestech.agenda_tech.security.AppLockManager
import com.filestech.agenda_tech.security.BiometricGate
import com.filestech.agenda_tech.system.notifications.ReminderNotifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.ceil

data class LockUiState(
    val lockEnabled: Boolean = false,
    val biometricEnabled: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val lockRepository: LockRepository,
    private val appLock: AppLockManager,
    private val reminderNotifier: ReminderNotifier,
    private val biometricGate: BiometricGate,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = AppSettings(),
    )

    val lockState: StateFlow<LockUiState> = combine(
        lockRepository.lockEnabled,
        lockRepository.biometricEnabled,
    ) { enabled, biometric ->
        LockUiState(lockEnabled = enabled, biometricEnabled = biometric)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LockUiState())

    fun setPin(pin: String) {
        viewModelScope.launch { lockRepository.setPin(pin) }
    }

    fun disableLock() {
        viewModelScope.launch { lockRepository.disableLock() }
    }

    /** Seconds the user must wait before the next re-auth attempt (0 when not throttled). */
    private var tickerJob: Job? = null

    private val _throttleSeconds = MutableStateFlow(0)
    val throttleSeconds: StateFlow<Int> = _throttleSeconds.asStateFlow()

    /**
     * LOCK-6 — re-auth gate: disabling the lock or changing the PIN must confirm the current PIN.
     * SEC-1 — routed through the shared [AppLockManager] so this path is under the SAME brute-force
     * back-off as the lock screen (an attacker past biometrics must not be able to guess the PIN
     * here without limit).
     *
     * Audit SEC-2 — "the SAME" is finally true. S16 made the lock screen's attempt atomic and left
     * this one split across `throttleRemainingMs` → ~100 ms of PBKDF2 with no lock held →
     * `registerFailedAttempt`, which is exactly the shape S16 removed, on the one screen where the
     * lock can be **switched off**. The claim above was written before S16 and became false with it —
     * the asymmetric-twin motif, produced by the correction of that very motif.
     */
    suspend fun verifyPin(pin: String): Boolean =
        when (val attempt = appLock.attemptPin { lockRepository.verifyPin(pin) }) {
            is AppLockManager.Attempt.Accepted -> true
            is AppLockManager.Attempt.Rejected -> {
                showThrottle(attempt.throttleMs)
                false
            }
            is AppLockManager.Attempt.Throttled -> {
                showThrottle(attempt.remainingMs)
                false
            }
        }

    /**
     * Publishes [remainingMs] before suspending, then ticks it down — identical to
     * [com.filestech.agenda_tech.ui.lock.LockViewModel]. The ticker's own first read suspends, so
     * without this the button re-enabled one dispatch before the countdown appeared: the very window
     * S16 was about to close on the other screen.
     */
    private fun showThrottle(remainingMs: Long) {
        if (remainingMs > 0) _throttleSeconds.value = ceil(remainingMs / MILLIS_PER_SECOND).toInt()
        startThrottleTicker()
    }

    /** Replaced, never stacked: one wrong PIN used to leak one coroutine (audit SEC-10). */
    private fun startThrottleTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            var remaining = appLock.throttleRemainingMs()
            while (remaining > 0) {
                _throttleSeconds.value = ceil(remaining / MILLIS_PER_SECOND).toInt()
                delay(THROTTLE_TICK_MS)
                remaining = appLock.throttleRemainingMs()
            }
            _throttleSeconds.value = 0
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            // Audit F3 — turning the gate off discards its Keystore key, so switching it back on
            // starts from a fresh one. Keeping the old key would leave a credential bound to the
            // enrolments of the moment it was created, outliving the preference that justified it.
            if (!enabled) biometricGate.reset()
            lockRepository.setBiometricEnabled(enabled)
        }
    }

    fun setThemeMode(value: ThemeMode) = update { it.copy(themeMode = value) }
    fun setWeekStart(value: WeekStart) = update { it.copy(weekStart = value) }
    fun setShowWeekNumbers(value: Boolean) = update { it.copy(showWeekNumbers = value) }
    fun setDefaultColor(value: CalendarColor) = update { it.copy(defaultEventColor = value) }
    fun setDefaultDuration(minutes: Int) = update { it.copy(defaultDurationMinutes = minutes) }
    fun setDefaultReminder(minutes: Int) = update { it.copy(defaultReminderMinutes = minutes) }
    fun setFlagSecure(value: Boolean) = update { it.copy(flagSecure = value) }
    fun setWidgetHideTitles(value: Boolean) = update { it.copy(widgetHideTitles = value) }
    fun setNotifSound(value: Boolean) = updateThenRebuildChannel { it.copy(notifSound = value) }

    /** [uri] null restores the system default notification sound. */
    fun setNotifSoundUri(uri: String?) = updateThenRebuildChannel { it.copy(notifSoundUri = uri) }
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
        const val MILLIS_PER_SECOND = 1000.0
        const val THROTTLE_TICK_MS = 500L
    }
}
