package com.filestech.agenda_tech.system.backup

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arms and disarms the weekly automatic backup.
 *
 * Deliberately declares **no constraints**. WorkManager offers "only on unmetered network", "only
 * while charging", "only when the battery is not low" — and the first is meaningless here (the app
 * has no network at all), while the others would let the very users who most need a backup, those who
 * never plug in, go months without one. A weekly `.atbak` costs a fraction of a second of CPU and a
 * few hundred kilobytes; the OS already batches it into a maintenance window.
 */
@Singleton
class AutoBackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun enable() {
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(INTERVAL_DAYS, TimeUnit.DAYS)
            // A quarter of the period in which the OS may place the run, so a fleet of devices does not
            // all wake at the same instant and so a phone that is off at the nominal time still gets a
            // chance later in the window.
            .setInitialDelay(INITIAL_DELAY_HOURS, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            AutoBackupWorker.UNIQUE_NAME,
            // KEEP, not UPDATE: re-arming on every app start would push the next run a full week away
            // each time, and a weekly backup that never fires is the worst possible outcome here.
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        Timber.i("AutoBackup: scheduled every %d days", INTERVAL_DAYS)
    }

    fun disable() {
        WorkManager.getInstance(context).cancelUniqueWork(AutoBackupWorker.UNIQUE_NAME)
        Timber.i("AutoBackup: schedule cancelled")
    }

    private companion object {
        const val INTERVAL_DAYS = 7L

        /** Never on the same minute the switch is flipped: the user has just made a manual backup possible. */
        const val INITIAL_DELAY_HOURS = 1L
    }
}
