package com.filestech.agenda_tech.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController

/**
 * Shows the lock screen **instead of** the app while [locked], and gives the user back the exact screen
 * they left once it is lifted.
 *
 * ## Why the navigation and the saved state live here, above the lock
 *
 * `MainActivity` used to switch between `LockScreen()` and `AppRoot()`, and `AppRoot` created its own
 * `NavController`. Locking therefore removed the whole navigation from composition: unlocking built a
 * new one on the month view, and every screen's `ViewModel` and `rememberSaveable` state were left
 * behind. A restore that was waiting for its password after the file picker never showed the
 * dialog again (reported on a Galaxy S9, 2026-09-15), and an event being typed when a call came in
 * was lost.
 *
 * The controller and a [rememberSaveableStateHolder] are created here, outside the lock switch. The
 * app content still **leaves composition** while locked — nothing of it is drawn, reachable by touch
 * or read by accessibility services, and its dialogs, being part of it, are gone too — but its state
 * is kept and restored on unlock.
 */
@Composable
fun LockedAppHost(
    locked: Boolean,
    lockScreen: @Composable () -> Unit,
    content: @Composable (NavHostController) -> Unit,
) {
    val navController = rememberNavController()
    val stateHolder = rememberSaveableStateHolder()
    if (locked) {
        lockScreen()
    } else {
        stateHolder.SaveableStateProvider(APP_CONTENT_KEY) { content(navController) }
    }
}

private const val APP_CONTENT_KEY = "app-content"
