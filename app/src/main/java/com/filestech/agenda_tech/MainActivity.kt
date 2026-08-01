package com.filestech.agenda_tech

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.filestech.agenda_tech.data.local.db.AppDatabase
import com.filestech.agenda_tech.data.local.db.DatabaseFactory
import com.filestech.agenda_tech.domain.repository.LockRepository
import com.filestech.agenda_tech.domain.repository.SettingsRepository
import com.filestech.agenda_tech.domain.settings.AppSettings
import com.filestech.agenda_tech.domain.settings.ThemeMode
import com.filestech.agenda_tech.security.AppLockManager
import com.filestech.agenda_tech.security.BiometricGate
import com.filestech.agenda_tech.security.LockState
import com.filestech.agenda_tech.security.StrongBiometrics
import com.filestech.agenda_tech.ui.AppRoot
import com.filestech.agenda_tech.ui.lock.LockScreen
import com.filestech.agenda_tech.ui.theme.AgendaTechTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Provider

/**
 * Single Compose host ([FragmentActivity] so `androidx.biometric` can attach its fragment). Theme
 * and screenshot-blocking flag are driven by [SettingsRepository]; the optional app lock (PIN or
 * biometric) gates the whole UI via [AppLockManager] and re-locks when backgrounded.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var lockRepository: LockRepository
    @Inject lateinit var appLock: AppLockManager
    @Inject lateinit var biometricGate: BiometricGate

    // ROB-NEW-1 — the DB must be built before consumeResetFlag() is read, so a reset is reported on
    // THIS launch and not the next one. A Provider (not a direct field) keeps that ordering without
    // paying for it on the Main thread: building it opens the Keystore (IPC, slow on StrongBox),
    // reads the wrapped key off disk and decrypts it — all of which onCreate resolves on IO below.
    @Inject lateinit var appDatabaseProvider: Provider<AppDatabase>

    /** Guards against two overlapping biometric prompts — see [showBiometricPrompt]. Main thread only. */
    private var biometricPromptInFlight = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { appLock.state.value == LockState.UNKNOWN }
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()

        // The splash stays up while the lock state is UNKNOWN, so this whole chain runs before any UI
        // is shown — without blocking the Main thread on Keystore/disk.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { appDatabaseProvider.get() }
            // SEC/ROB-1 — if an unrecoverable Keystore failure forced a DB reset, tell the user (once)
            // rather than losing their data silently. Read only after the DB was actually built.
            if (DatabaseFactory.consumeResetFlag(this@MainActivity)) {
                Toast.makeText(this@MainActivity, R.string.db_reset_notice, Toast.LENGTH_LONG).show()
            }
            if (lockRepository.isLockEnabled()) appLock.lock() else appLock.unlock()
        }

        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            val lockState by appLock.state.collectAsStateWithLifecycle()
            val useDarkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            // LOCK-2 — keep FLAG_SECURE forced whenever the lock is active or unresolved, regardless
            // of the user's "block screenshots" preference: the lock screen (and the Recents snapshot
            // taken as we background) must never leak the PIN field or calendar content.
            LaunchedEffect(settings.flagSecure, lockState) {
                val forceSecure = settings.flagSecure || lockState != LockState.UNLOCKED
                if (forceSecure) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            AgendaTechTheme(useDarkTheme = useDarkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (lockState) {
                        LockState.UNKNOWN -> Unit // splash keeps covering until resolved
                        LockState.LOCKED -> LockScreen(onRequestBiometric = ::showBiometricPrompt)
                        LockState.UNLOCKED -> AppRoot()
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Re-lock when the app leaves the foreground.
        lifecycleScope.launch {
            if (lockRepository.isLockEnabled()) appLock.lock()
        }
    }

    private fun showBiometricPrompt() {
        // Audit F3 — Class 3 only; the rationale and the tier live in StrongBiometrics so the prompt
        // and the settings toggle can never disagree about what "biometric unlock" means.
        val allowed = StrongBiometrics.allowedAuthenticators
        if (!StrongBiometrics.isAvailable(this)) return

        // The lock screen asks automatically on display and again on every tap of its button, and the
        // preparation below is slow enough to sit between the two. Two overlapping calls would each
        // reach authenticate() and fight over the same prompt fragment, so only one runs at a time.
        if (biometricPromptInFlight) return
        biometricPromptInFlight = true

        lifecycleScope.launch {
            try {
                // Audit F3 — the unlock is gated on a Keystore operation the OS only permits after a
                // Class 3 authentication, not on the success callback alone. Preparing it generates
                // the key on first use: an IPC to keystore2, slow enough on StrongBox to matter, so it
                // stays off the Main thread like every other Keystore call in this app.
                val preparation = withContext(Dispatchers.IO) { biometricGate.prepare() }
                val cipher = when (preparation) {
                    is BiometricGate.Preparation.Ready -> preparation.cipher
                    BiometricGate.Preparation.Invalidated -> {
                        onBiometricKeyInvalidated()
                        return@launch
                    }
                    BiometricGate.Preparation.Unavailable -> {
                        // The button is drawn from canAuthenticate(), which says nothing about whether
                        // the Keystore can actually produce a cipher. Say so instead of doing nothing.
                        Toast.makeText(this@MainActivity, R.string.lock_biometric_failed, Toast.LENGTH_LONG)
                            .show()
                        return@launch
                    }
                }

                val prompt = BiometricPrompt(
                    this@MainActivity,
                    ContextCompat.getMainExecutor(this@MainActivity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (biometricGate.verify(result.cryptoObject?.cipher)) {
                                appLock.unlock()
                            } else {
                                // The prompt said yes but the crypto could not run: precisely the case
                                // the CryptoObject exists to catch. Stay locked; the PIN is on screen.
                                Toast.makeText(
                                    this@MainActivity,
                                    R.string.lock_biometric_failed,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                )
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(getString(R.string.lock_biometric_title))
                    .setNegativeButtonText(getString(R.string.lock_use_pin))
                    .setAllowedAuthenticators(allowed)
                    .build()
                // authenticate() commits a fragment transaction, which throws once the activity has
                // saved its state — reachable by simply leaving the app while the key is being
                // prepared, since lifecycleScope survives onStop. Waiting for RESUMED rather than
                // bailing out means the prompt is still there when the user comes back, and a
                // destroyed activity cancels this coroutine instead of crashing it.
                lifecycle.withResumed { prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher)) }
            } finally {
                biometricPromptInFlight = false
            }
        }
    }

    /**
     * Biometrics were re-enrolled, so the gate key is gone for good. Turn the preference off — it can
     * no longer be honoured — and say so, instead of leaving a button that fails every time.
     */
    private fun onBiometricKeyInvalidated() {
        lifecycleScope.launch {
            lockRepository.setBiometricEnabled(false)
            Toast.makeText(
                this@MainActivity,
                R.string.lock_biometric_enrollment_changed,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
