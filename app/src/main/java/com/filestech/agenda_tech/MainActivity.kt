package com.filestech.agenda_tech

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.filestech.agenda_tech.ui.AppRoot
import com.filestech.agenda_tech.ui.theme.AgendaTechTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single Compose host. Extends [ComponentActivity] for now; when the optional biometric vault
 * lock lands (phase 2) this swaps to `FragmentActivity` so `androidx.biometric` can attach its
 * fragment — a strict super-set change, invisible to the rest of the app.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Reminders degrade gracefully if denied (the notifier no-ops), so we ignore the result.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()

        // Privacy-preserving default: keep the agenda out of the Recents thumbnail and block
        // screenshots. A user-facing toggle to relax this belongs to the settings phase.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        enableEdgeToEdge()

        setContent {
            AgendaTechTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
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
