package com.filestech.agenda_tech

import android.app.Application
import com.filestech.agenda_tech.core.logging.LineNumberDebugTree
import com.filestech.agenda_tech.core.logging.NoOpReleaseTree
import com.filestech.agenda_tech.di.ApplicationScope
import com.filestech.agenda_tech.domain.repository.SettingsRepository
import com.filestech.agenda_tech.domain.usecase.EnsureDefaultCalendarUseCase
import com.filestech.agenda_tech.system.backup.AutoBackupScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class MainApplication : Application() {

    /**
     * A Provider, not a direct field — the same reasoning as `MainActivity`'s `Provider<AppDatabase>`.
     * Injecting the use case eagerly would make Hilt resolve its whole graph during
     * `super.onCreate()`, i.e. on the Main thread: use case → CalendarRepository → CalendarDao →
     * AppDatabase → `DatabaseFactory.build()`, which opens the Keystore, reads and decrypts the
     * wrapped key, opens SQLCipher and — on a device carrying a legacy zero-key database — rewrites
     * every page to rekey it (audit SEC-1). None of that belongs on the Main thread at startup.
     */
    @Inject lateinit var ensureDefaultCalendar: Provider<EnsureDefaultCalendarUseCase>

    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    /** Providers for the same reason as above: neither may pull the database graph onto Main. */
    @Inject lateinit var settingsRepository: Provider<SettingsRepository>
    @Inject lateinit var autoBackupScheduler: Provider<AutoBackupScheduler>

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.LOG_ENABLED) {
            Timber.plant(LineNumberDebugTree())
        } else {
            Timber.plant(NoOpReleaseTree())
        }

        // First-run bootstrap: make sure a calendar exists so events always have a home.
        // Idempotent + off the main thread; failure is logged, never fatal.
        // The name is resolved here (cheap) while the database itself is built on IO below, where
        // `get()` first touches the Keystore and disk.
        val defaultCalendarName = getString(R.string.default_calendar_name)
        appScope.launch {
            runCatching { withContext(Dispatchers.IO) { ensureDefaultCalendar.get()(defaultCalendarName) } }
                .onFailure { Timber.w(it, "ensureDefaultCalendar failed") }
        }

        // Re-arm the weekly backup if it should be running.
        //
        // WorkManager already survives a reboot on its own, so this is not about that. It is about the
        // cases where its own database does NOT survive and the setting does: a reinstall over the top,
        // a restore onto a new phone, "clear storage" on the WorkManager process. The setting would
        // then say "on", the screen would show a date, and nothing would ever be written again.
        //
        // `ExistingPeriodicWorkPolicy.KEEP` makes this a no-op when the work is already queued, so the
        // next run is never pushed back by simply opening the app.
        appScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (settingsRepository.get().current().autoBackupEnabled) {
                        autoBackupScheduler.get().enable()
                    }
                }
            }.onFailure { Timber.w(it, "auto-backup re-arm failed") }
        }
    }
}
