package com.filestech.agenda_tech.system.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.filestech.agenda_tech.domain.repository.SettingsRepository
import com.filestech.agenda_tech.domain.usecase.RunAutoBackupUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import java.time.ZoneId

/**
 * Runs one automatic backup, on WorkManager's schedule.
 *
 * Dependencies come through a Hilt [AutoBackupEntryPoint] rather than `hilt-work`, for the same
 * reason the widget does it: a Worker starts outside the Activity graph, and this way the feature
 * adds no new annotation processor, no `HiltWorkerFactory`, and no change to the Application class or
 * the manifest's WorkManager initializer. `androidx.work` is declared explicitly at the version Glance
 * already resolves, so the dependency graph is unchanged.
 *
 * **Always returns success.** Not because nothing can go wrong — [RunAutoBackupUseCase] records every
 * failure for the Backup screen to show — but because a periodic Worker that returns `retry` on a
 * folder the user revoked would wake the device again and again for something only the user can fix.
 * The weekly schedule is the retry.
 */
class AutoBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AutoBackupEntryPoint {
        fun runAutoBackup(): RunAutoBackupUseCase
        fun settingsRepository(): SettingsRepository
    }

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            AutoBackupEntryPoint::class.java,
        )
        return try {
            // The switch may have been turned off while this run was queued. WorkManager cancellation
            // is not instantaneous, and writing a backup the user just declined would be a surprise.
            if (!entryPoint.settingsRepository().current().autoBackupEnabled) {
                Timber.i("AutoBackup: skipped, the setting is off")
                return Result.success()
            }
            val outcome = entryPoint.runAutoBackup()(
                nowUtcMillis = System.currentTimeMillis(),
                zone = ZoneId.systemDefault(),
            )
            if (outcome.isFailure) Timber.w("AutoBackup: finished as %s", outcome)
            Result.success()
        } catch (t: Throwable) {
            // The use case does not throw, so reaching here means the Hilt graph itself could not be
            // built — nothing a retry fixes, and nothing worth crashing a background process over.
            Timber.e(t, "AutoBackup: worker could not run")
            Result.success()
        }
    }

    companion object {
        const val UNIQUE_NAME = "agenda-tech-auto-backup"
    }
}
