package com.filestech.agenda_tech

import android.app.Application
import com.filestech.agenda_tech.core.logging.LineNumberDebugTree
import com.filestech.agenda_tech.core.logging.NoOpReleaseTree
import com.filestech.agenda_tech.di.ApplicationScope
import com.filestech.agenda_tech.domain.usecase.EnsureDefaultCalendarUseCase
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
    }
}
