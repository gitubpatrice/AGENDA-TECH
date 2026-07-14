package com.filestech.agenda_tech

import android.app.Application
import com.filestech.agenda_tech.core.logging.LineNumberDebugTree
import com.filestech.agenda_tech.core.logging.NoOpReleaseTree
import com.filestech.agenda_tech.di.ApplicationScope
import com.filestech.agenda_tech.domain.usecase.EnsureDefaultCalendarUseCase
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class MainApplication : Application() {

    @Inject lateinit var ensureDefaultCalendar: EnsureDefaultCalendarUseCase

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
        appScope.launch {
            runCatching { ensureDefaultCalendar(getString(R.string.default_calendar_name)) }
                .onFailure { Timber.w(it, "ensureDefaultCalendar failed") }
        }
    }
}
