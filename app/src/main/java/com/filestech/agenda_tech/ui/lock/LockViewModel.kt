package com.filestech.agenda_tech.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.domain.repository.LockRepository
import com.filestech.agenda_tech.security.AppLockManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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

    /** The single countdown coroutine; replaced, never stacked — see [startThrottleTicker]. */
    private var tickerJob: Job? = null

    /** Seconds the user must wait before the next PIN attempt (0 when not throttled). */
    private val _throttleSeconds = MutableStateFlow(0)
    val throttleSeconds: StateFlow<Int> = _throttleSeconds.asStateFlow()

    init {
        // Reflect any residual back-off left from a previous LockScreen instance.
        startThrottleTicker()
    }

    /**
     * Audit S16 — one call, one guess.
     *
     * This used to read the back-off, then verify, then record, each under its own lock with ~100 ms
     * of PBKDF2 in between. Every tap landing in that gap saw a back-off of zero and got a free
     * attempt, so a burst of taps spent several guesses inside a single 60-second window. The
     * decision now lives entirely inside [AppLockManager.attemptPin], which holds its mutex across
     * the verification.
     */
    fun submitPin(pin: String) {
        viewModelScope.launch {
            when (val attempt = appLock.attemptPin { lockRepository.verifyPin(pin) }) {
                is AppLockManager.Attempt.Accepted -> appLock.unlock()
                is AppLockManager.Attempt.Rejected -> {
                    _wrongPin.value = true
                    showThrottle(attempt.throttleMs)
                }
                is AppLockManager.Attempt.Throttled -> showThrottle(attempt.remainingMs)
            }
        }
    }

    fun clearError() {
        _wrongPin.value = false
    }

    fun onBiometricSuccess() {
        viewModelScope.launch {
            appLock.resetAttempts()
            appLock.unlock()
        }
    }

    /**
     * Shows [remainingMs] straight away, then ticks it down.
     *
     * Publishing the value the attempt already returned matters: the ticker's own first read is a
     * suspending call, so the button stayed enabled for one dispatch after a refused attempt — the
     * window a burst of taps used. Now the countdown is visible before this function suspends at all.
     */
    private fun showThrottle(remainingMs: Long) {
        if (remainingMs > 0) _throttleSeconds.value = ceil(remainingMs / MILLIS_PER_SECOND).toInt()
        startThrottleTicker()
    }

    /**
     * Ticks the visible countdown down to zero while a back-off is in effect.
     *
     * Cancels the previous ticker first: every refused attempt used to launch one more, so a handful
     * of wrong PINs left several coroutines writing the same StateFlow. They converged, but a
     * coroutine leaked per failure and the last writer to lose the race could publish a stale second.
     */
    private fun startThrottleTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            var remaining = appLock.throttleRemainingMs()
            while (remaining > 0) {
                _throttleSeconds.value = ceil(remaining / MILLIS_PER_SECOND).toInt()
                delay(TICK_MS)
                remaining = appLock.throttleRemainingMs()
            }
            _throttleSeconds.value = 0
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val TICK_MS = 500L
        const val MILLIS_PER_SECOND = 1000.0
    }
}
