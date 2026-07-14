package com.filestech.agenda_tech

import android.app.Application
import com.filestech.agenda_tech.core.logging.LineNumberDebugTree
import com.filestech.agenda_tech.core.logging.NoOpReleaseTree
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.LOG_ENABLED) {
            Timber.plant(LineNumberDebugTree())
        } else {
            Timber.plant(NoOpReleaseTree())
        }
    }
}
