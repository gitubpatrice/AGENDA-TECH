package com.filestech.agenda_tech

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
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
import com.filestech.agenda_tech.data.local.db.AppDatabase
import com.filestech.agenda_tech.data.local.db.DatabaseFactory
import com.filestech.agenda_tech.domain.repository.LockRepository
import com.filestech.agenda_tech.domain.repository.SettingsRepository
import com.filestech.agenda_tech.domain.settings.AppSettings
import com.filestech.agenda_tech.domain.settings.ThemeMode
import com.filestech.agenda_tech.security.AppLockManager
import com.filestech.agenda_tech.security.LockState
import com.filestech.agenda_tech.ui.AppRoot
import com.filestech.agenda_tech.ui.lock.LockScreen
import com.filestech.agenda_tech.ui.theme.AgendaTechTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    // ROB-NEW-1 — injecting the DB forces DatabaseFactory.build() (and any reset-on-failure) to run
    // now, so consumeResetFlag() below sees a reset that happened on THIS launch, not the next one.
    @Inject lateinit var appDatabase: AppDatabase

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { appLock.state.value == LockState.UNKNOWN }
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()
        // SEC/ROB-1 — if an unrecoverable Keystore failure forced a DB reset, tell the user (once)
        // rather than losing their data silently.
        if (DatabaseFactory.consumeResetFlag(this)) {
            android.widget.Toast.makeText(this, getString(R.string.db_reset_notice), android.widget.Toast.LENGTH_LONG).show()
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()

        lifecycleScope.launch {
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
        val allowed = BIOMETRIC_STRONG or BIOMETRIC_WEAK
        if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) return

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    appLock.unlock()
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.lock_biometric_title))
            .setNegativeButtonText(getString(R.string.lock_use_pin))
            .setAllowedAuthenticators(allowed)
            .build()
        prompt.authenticate(info)
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
