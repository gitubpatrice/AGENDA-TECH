package com.filestech.agenda_tech.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG

/**
 * The single place that decides which biometric tier may open the app (audit F3).
 *
 * Class 2 ([BiometricManager.Authenticators.BIOMETRIC_WEAK]) is exactly the tier the platform
 * forbids from gating Keystore keys, because it is spoofable by a photo on many OEM face-unlock
 * builds. The unlock prompt is the only thing between someone holding the phone and the whole
 * agenda, so it accepts Class 3 only; a device with nothing stronger falls back to the PIN, which is
 * throttled. `DEVICE_CREDENTIAL` stays out: it would re-admit the same weak tier indirectly.
 *
 * The policy lives here rather than at each call site because it had already drifted once — the
 * prompt was hardened while the settings toggle kept offering Class 2, which left the toggle
 * switchable on a Class 2 device and the resulting unlock silently doing nothing. Availability and
 * the authenticator mask handed to [androidx.biometric.BiometricPrompt] must always be the same
 * question.
 */
object StrongBiometrics {

    /** Authenticator mask to hand to both `canAuthenticate` and `PromptInfo.setAllowedAuthenticators`. */
    val allowedAuthenticators: Int = BIOMETRIC_STRONG

    /** True when this device can actually satisfy [allowedAuthenticators] right now. */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(allowedAuthenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS
}
