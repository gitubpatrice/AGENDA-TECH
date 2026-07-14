package com.filestech.agenda_tech.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.domain.repository.LockRepository
import com.filestech.agenda_tech.security.AppLockManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.ceil

@HiltViewModel
class LockViewModel @Inject constructor(
    private val lockRepository: LockRepository,
    private val appLock: AppLockManager,
) : ViewModel() {

    val biometricEnabled: StateFlow<Boolean> = lockRepository.biometricEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    private val _wrongPin = MutableStateFlow(false)
    val wrongPin: StateFlow<Boolean> = _wrongPin.asStateFlow()

    /** Seconds the user must wait before the next PIN attempt (0 when not throttled). */
    private val _throttleSeconds = MutableStateFlow(0)
    val throttleSeconds: StateFlow<Int> = _throttleSeconds.asStateFlow()

    init {
        // Reflect any residual back-off left from a previous LockScreen instance.
        startThrottleTicker()
    }

    fun submitPin(pin: String) {
        if (appLock.throttleRemainingMs() > 0) {
            startThrottleTicker()
            return
        }
        viewModelScope.launch {
            if (lockRepository.verifyPin(pin)) {
                appLock.resetAttempts()
                appLock.unlock()
            } else {
                appLock.registerFailedAttempt()
                _wrongPin.value = true
                startThrottleTicker()
            }
        }
    }

    fun clearError() {
        _wrongPin.value = false
    }

    fun onBiometricSuccess() {
        appLock.resetAttempts()
        appLock.unlock()
    }

    /** Ticks the visible countdown down to zero while a back-off is in effect. */
    private fun startThrottleTicker() {
        viewModelScope.launch {
            var remaining = appLock.throttleRemainingMs()
            while (remaining > 0) {
                _throttleSeconds.value = ceil(remaining / 1000.0).toInt()
                delay(TICK_MS)
                remaining = appLock.throttleRemainingMs()
            }
            _throttleSeconds.value = 0
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val TICK_MS = 500L
    }
}
