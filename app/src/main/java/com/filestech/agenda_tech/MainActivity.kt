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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.filestech.agenda_tech.data.local.db.AppDatabase
import com.filestech.agenda_tech.core.prefs.OneShotFlag
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
import com.filestech.agenda_tech.ui.StartupFailureScreen
import com.filestech.agenda_tech.ui.lock.LockScreen
import com.filestech.agenda_tech.ui.theme.AgendaTechTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
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

    /**
     * The latest value of [LockRepository.lockEnabled], readable **without suspending**.
     *
     * Audit F13 — [onStop] used to launch a coroutine to answer "is a lock configured?" before it
     * could re-lock. The system takes the Recents snapshot *around* `onStop`, not after whatever it
     * started has finished, so with "block screenshots" off and a PIN configured the window was still
     * `UNLOCKED` — and therefore still without `FLAG_SECURE` — at the moment the snapshot was taken.
     * The LOCK-2 guarantee stated in `SECURITY.md` ("the Recents snapshot can never leak") did not
     * hold in exactly the configuration it was written for.
     *
     * Mirroring the flow into a field is what makes the decision synchronous.
     *
     * ## Why `null` and not `false` (audit S3)
     *
     * The first version of this field defaulted to `false`, i.e. "no lock configured", before anything
     * had been read. That traded a timing defect for a **fail-open** one: an Activity recreated while
     * the process was already `UNLOCKED` — a configuration change outside the `configChanges` list, or
     * "don't keep activities" — could reach `onStop` in that window, and `onStop` would then do
     * nothing at all: no `FLAG_SECURE`, no re-lock. This repo has treated failing open as unacceptable
     * since F1; a default that means "no protection" is exactly that.
     *
     * Three states, and the two questions are answered differently on purpose:
     *  - raising `FLAG_SECURE` treats `null` like "locked" — it costs nothing, it is reversible on the
     *    next resume, and a Recents snapshot cannot be taken back;
     *  - re-locking requires a firm `true` — locking with no PIN configured would strand the user on a
     *    lock screen with nothing to type, which is the one outcome worse than a missed re-lock.
     *
     * `@Volatile` by prudence rather than by necessity: both writers and the reader are on the Main
     * thread today (`lifecycleScope` is `Dispatchers.Main.immediate`), but nothing in the type says so.
     */
    @Volatile
    private var lockConfigured: Boolean? = null

    /**
     * The latest value of the user's "block screenshots" preference, readable without suspending.
     *
     * Audit SEC-5 — [onPause] raises `FLAG_SECURE` unconditionally, and the comment there used to
     * claim the `LaunchedEffect` would clear it again on resume. It cannot: that effect is keyed on
     * `(flagSecure, lockState)`, and **neither changes** when the app comes back from an `onPause`
     * that was never followed by an `onStop` — a system permission dialog, a share sheet, multi-window.
     * So a user who had explicitly allowed screenshots lost them, silently, until something else
     * happened to move the lock state.
     *
     * Raising and clearing now happen at the same level of the lifecycle instead of one being
     * delegated to an effect that does not observe the resume.
     */
    @Volatile
    private var blockScreenshots = false

    /**
     * True when the database refused to open, so the UI shows an explanation instead of nothing.
     *
     * Compose state rather than a Toast (audit DR-1): the message has to stay on screen — it tells
     * the user their agenda is intact and what to do — and a Toast on a process that has nothing else
     * to display would flash once and leave a blank app.
     */
    private var startupFailure by mutableStateOf(false)

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
            // Audit DR-1 — the other half of D1.
            //
            // Removing `.fallbackToDestructiveMigrationOnDowngrade(false)` was right: it armed the
            // wipe it claimed to forbid. But it was also the only thing that ever *recovered* from an
            // unopenable database, and nothing replaced it. Room now throws, this coroutine had no
            // guard, and an uncaught throw out of `lifecycleScope.launch` kills the process — behind
            // a splash that never lifts, on every launch, for ever. `allowBackup=false`, no export
            // possible because the app never starts: the only way out was "clear data", i.e. exactly
            // the erasure D1 removed, inflicted by hand and with no explanation.
            //
            // The trade is deliberate and unchanged — the agenda is NOT touched. What changes is that
            // the user is told so, and told what their options are, instead of watching the app die.
            val opened = runCatching { withContext(Dispatchers.IO) { appDatabaseProvider.get() } }
            if (opened.isFailure) {
                Timber.e(
                    "Database refused to open (%s) — data left untouched",
                    opened.exceptionOrNull()?.javaClass?.simpleName,
                )
                startupFailure = true
                appLock.unlock() // lifts the splash so the message can be shown at all
                return@launch
            }
            // SEC/ROB-1 — if an unrecoverable Keystore failure forced a DB reset, tell the user (once)
            // rather than losing their data silently. Read only after the DB was actually built.
            if (DatabaseFactory.consumeResetFlag(this@MainActivity)) {
                Toast.makeText(this@MainActivity, R.string.db_reset_notice, Toast.LENGTH_LONG).show()
            }
            // Audit S15 — same rule, other file: a corrupted settings store is replaced with defaults,
            // and since it carries `lock_enabled` that silently switches the app lock off. The lock
            // cannot be restored (the PIN wrap died with the file, and locking someone out of their
            // own agenda with nothing to type would be worse), so the least we owe them is to say it.
            if (OneShotFlag.SETTINGS_RESET.consume(this@MainActivity)) {
                Toast.makeText(this@MainActivity, R.string.settings_reset_notice, Toast.LENGTH_LONG).show()
            }
            val enabled = lockRepository.isLockEnabled()
            // Settles [lockConfigured] on the very first pass, so the window where onStop knows
            // nothing is as short as the splash — see the field's KDoc.
            lockConfigured = enabled
            if (enabled) appLock.lock() else appLock.unlock()
        }

        // Kept up to date for [onStop], which cannot wait for a suspend read. Collected on
        // lifecycleScope rather than repeatOnLifecycle on purpose: the value has to be current
        // *while the Activity is stopped*, which is the one moment repeatOnLifecycle would not cover.
        lifecycleScope.launch {
            lockRepository.lockEnabled.collect { lockConfigured = it }
        }

        // Audit SEC-5 — le pendant de [lockConfigured] pour le réglage « bloquer les captures ».
        // [onResume] doit pouvoir rabaisser le drapeau sans attendre une lecture suspendue, pour la
        // même raison qu'onStop doit pouvoir le lever.
        lifecycleScope.launch {
            settingsRepository.settings.collect { blockScreenshots = it.flagSecure }
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
                    when {
                        startupFailure -> StartupFailureScreen()
                        lockState == LockState.UNKNOWN -> Unit // splash keeps covering until resolved
                        lockState == LockState.LOCKED ->
                            LockScreen(onRequestBiometric = ::showBiometricPrompt)
                        else -> AppRoot()
                    }
                }
            }
        }
    }

    /**
     * Audit S5 — raises `FLAG_SECURE` one lifecycle step earlier than the re-lock.
     *
     * `onStop` was already synchronous (F13), but "synchronous" only orders our own two statements.
     * `Window.addFlags` reaches WindowManagerService through `requestLayout()`, i.e. on the **next**
     * traversal, while the task snapshot is taken by WMS around the same transition. That the flag
     * wins that race was never measured, and `SECURITY.md` was claiming the snapshot "can never leak".
     *
     * `onPause` precedes `onStop` by several frames in every path, which gives the traversal time to
     * land. It is cheap because [onResume] lowers it again when the preference allows — an earlier
     * version of this comment said the `LaunchedEffect` would, which was false: that effect is keyed
     * on `(flagSecure, lockState)` and a resume changes neither (audit SEC-5).
     *
     * **Locking** stays in `onStop` on purpose: the biometric prompt goes through `onPause`, so
     * re-locking here would fight the very unlock the user just started.
     */
    override fun onPause() {
        super.onPause()
        if (lockConfigured != false) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /**
     * Undoes what [onPause] did, when — and only when — the user asked for it.
     *
     * Symmetry with [onPause] is the whole point (audit SEC-5): the flag is raised at a lifecycle
     * callback, so it has to be lowered at one too. Delegating the lowering to the `LaunchedEffect`
     * looked equivalent and was not, because that effect only runs when its keys change and a resume
     * changes neither.
     *
     * The two conditions are the same ones the effect applies, read synchronously: nothing is lowered
     * while the lock still guards the screen, or while its state is unresolved.
     */
    override fun onResume() {
        super.onResume()
        if (!blockScreenshots && appLock.state.value == LockState.UNLOCKED) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onStop() {
        super.onStop()
        // Audit F13 — re-lock when the app leaves the foreground, synchronously.
        //
        // FLAG_SECURE is raised again rather than left to the LaunchedEffect that normally owns it:
        // that effect reacts to the lock state, so it can only run *after* the state has changed,
        // which is one frame too late. [onPause] has already raised it; this is the belt to that
        // brace, and it costs one idempotent call.
        //
        // Nothing is cleared when no lock is configured: that is the user's "allow screenshots"
        // preference, and honouring it is the whole reason the flag is not simply pinned on.
        //
        // The two questions are answered differently, and the asymmetry is the point (audit S3):
        // "unknown" protects the snapshot, because that is free and reversible; only a firm "yes"
        // locks, because locking without a PIN to type is a dead end. See [lockConfigured].
        if (lockConfigured != false) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (lockConfigured == true) appLock.lock()
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
                            // Audit S6 — this callback is delivered on the Main executor, and
                            // `verify()` runs `cipher.doFinal` on an AndroidKeyStore key: another IPC
                            // to keystore2. The comment twenty lines above says every Keystore call in
                            // this app stays off the Main thread; this one did not, so the claim was
                            // false at the moment it mattered most — the tap that unlocks the app.
                            val cipherUsed = result.cryptoObject?.cipher
                            lifecycleScope.launch {
                                val ok = withContext(Dispatchers.IO) { biometricGate.verify(cipherUsed) }
                                if (ok) {
                                    appLock.unlock()
                                } else {
                                    // The prompt said yes but the crypto could not run: precisely the
                                    // case the CryptoObject exists to catch. Stay locked; PIN is on screen.
                                    Toast.makeText(
                                        this@MainActivity,
                                        R.string.lock_biometric_failed,
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
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
