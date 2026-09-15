package com.filestech.agenda_tech.ui.util

import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Told when the app is about to open another activity for a result, so that activity does not lock
 * the app behind the user's back. `MainActivity` provides the real one
 * ([com.filestech.agenda_tech.security.PickerRelockPolicy]).
 */
fun interface ExternalActivityGuard {
    fun onExternalActivityLaunched()
}

/**
 * The default does nothing, which means **the lock still happens**: a screen composed outside
 * `MainActivity` (a preview, a test) fails closed, never open.
 */
val LocalExternalActivityGuard = staticCompositionLocalOf { ExternalActivityGuard { } }

/**
 * Same `launch` as the platform launcher, with the guard told first — unless [sparesLock] is false.
 */
class AppResultLauncher<I> internal constructor(
    private val launcher: ManagedActivityResultLauncher<I, *>,
    private val guard: ExternalActivityGuard,
    private val sparesLock: Boolean,
) {
    fun launch(input: I) {
        if (sparesLock) guard.onExternalActivityLaunched()
        launcher.launch(input)
    }
}

/**
 * Whether opening [this] contract should spare the app its re-lock.
 *
 * **Not a permission request.** The system permission dialog is translucent: it pauses the activity
 * without stopping it, so it never triggers the re-lock and needs no pass. Giving it one opened a
 * hole found in external review (Gemini Pro, 2026-09-15): request a permission, press Home within the
 * launch window, and the stop that follows was spared — the app reopened unlocked from Recents.
 *
 * Decided by type rather than by a flag at each call site, so a permission launcher added later cannot
 * forget it. On a device whose permission UI does stop the activity, the app simply locks: the safe side.
 */
internal fun ActivityResultContract<*, *>.sparesRelock(): Boolean =
    this !is ActivityResultContracts.RequestPermission &&
        this !is ActivityResultContracts.RequestMultiplePermissions

/**
 * **The only way this app opens an activity for a result.** A drop-in for
 * `rememberLauncherForActivityResult`, which nothing else may call — enforced by
 * `AppResultLauncherIsTheOnlySeamTest`.
 *
 * One seam rather than a line added at each call site: eight launchers across five screens, and the
 * defect this app keeps meeting is the twin path fixed on one side only. A ninth launcher written
 * later with the platform API would silently lock the user out of its own flow again.
 */
@Composable
fun <I, O> rememberAppResultLauncher(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): AppResultLauncher<I> {
    val launcher = rememberLauncherForActivityResult(contract, onResult)
    val guard = LocalExternalActivityGuard.current
    return remember(launcher, guard) { AppResultLauncher(launcher, guard, contract.sparesRelock()) }
}
