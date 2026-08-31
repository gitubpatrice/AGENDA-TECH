package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.core.crypto.wipe
import com.filestech.agenda_tech.core.result.Outcome
import com.filestech.agenda_tech.di.IoDispatcher
import com.filestech.agenda_tech.domain.backup.AutoBackupOutcome
import com.filestech.agenda_tech.domain.backup.AutoBackupSecret
import com.filestech.agenda_tech.domain.backup.AutoBackupTarget
import com.filestech.agenda_tech.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One unattended backup: export the whole agenda, seal it, write it into the user's folder, and drop
 * the oldest automatic file once there are more than [KEEP].
 *
 * Records its own result in the settings ([AutoBackupOutcome]) whatever happens, because nobody is
 * watching when it runs. A backup feature that fails quietly is worse than none: the user believes
 * they are covered right up to the day they need the file.
 *
 * **Never throws.** It is called from a `Worker`, and an exception there is a crash in the background
 * with no user in front of it. That includes the recording step itself — writing to DataStore can
 * fail on a full disk, and losing the trace of a run is not worth crashing over.
 */
@Singleton
class RunAutoBackupUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val secret: AutoBackupSecret,
    private val target: AutoBackupTarget,
    private val exportBackup: ExportBackupUseCase,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Serialises the runs.
     *
     * The Worker and the screen's "Back up now" call this same singleton, and both write the file
     * named after today's date. Two at once would race over that one name. A `Mutex` rather than a
     * "busy" flag because the second caller should wait for the first and then see a real result, not
     * be silently turned away.
     */
    private val running = Mutex()

    suspend operator fun invoke(nowUtcMillis: Long, zone: ZoneId): AutoBackupOutcome =
        withContext(io) {
            running.withLock {
                val outcome = runCatching { runOnce(nowUtcMillis, zone) }
                    .getOrElse {
                        Timber.e(it, "AutoBackup: unexpected failure")
                        AutoBackupOutcome.WRITE_FAILED
                    }
                if (outcome == null) {
                    // Abandoned mid-run because the user turned the feature off. Nothing was written,
                    // and nothing is recorded: a failure line about a run they themselves cancelled
                    // would be noise, and the section is hidden while the switch is off anyway.
                    Timber.i("AutoBackup: abandoned, the switch went off mid-run")
                    return@withLock AutoBackupOutcome.NEVER_RUN
                }
                // Guarded separately: the contract above says this never throws, and DataStore can.
                runCatching { record(outcome, nowUtcMillis) }
                    .onFailure { Timber.e(it, "AutoBackup: could not record the outcome") }
                outcome
            }
        }

    /** Null when the run was abandoned before writing anything (see [invoke]). */
    private suspend fun runOnce(nowUtcMillis: Long, zone: ZoneId): AutoBackupOutcome? {
        preflight()?.let { return it }
        return exportAndWrite(nowUtcMillis, zone)
    }

    /**
     * What stops the run before any work is done, or null to go ahead.
     *
     * Both checks happen **before** the export because sealing costs about a second of deliberate
     * PBKDF2 work, and there is no point spending it to then discover there is nowhere to put the
     * result — on a phone whose card is out, that would be a second of CPU burnt every week for
     * nothing.
     */
    private suspend fun preflight(): AutoBackupOutcome? {
        if (settingsRepository.current().autoBackupFolderUri == null) return AutoBackupOutcome.NO_FOLDER
        if (!target.isWritable()) return AutoBackupOutcome.FOLDER_UNAVAILABLE
        return null
    }

    private suspend fun exportAndWrite(nowUtcMillis: Long, zone: ZoneId): AutoBackupOutcome? {
        val password = secret.read() ?: return AutoBackupOutcome.NO_PASSWORD
        // `password` is wiped by the exporter, on success and on failure alike — never wiped here.
        val export = when (val result = exportBackup(password, nowUtcMillis)) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> {
                Timber.e("AutoBackup: export failed (%s)", result.error)
                return AutoBackupOutcome.EXPORT_FAILED
            }
        }

        return try {
            // Re-checked here and not only at the start: the export above takes about a second, and
            // WorkManager's cancellation is not instantaneous. Writing a backup the user switched off
            // while it was in flight would be a surprise, and the point of the switch is that it is
            // obeyed.
            if (!settingsRepository.current().autoBackupEnabled) return null
            val isoDate = DateTimeFormatter.ISO_LOCAL_DATE
                .format(Instant.ofEpochMilli(nowUtcMillis).atZone(zone))
            if (target.write(fileNameFor(isoDate), export.bytes)) {
                rotate(export.events)
            } else {
                AutoBackupOutcome.WRITE_FAILED
            }
        } finally {
            // The sealed bytes are ciphertext, so this is not a secrecy measure — it just keeps a
            // multi-megabyte array from lingering in a background process's heap.
            export.bytes.wipe()
        }
    }

    private suspend fun rotate(events: Int): AutoBackupOutcome {
        val removed = target.prune(KEEP)
        Timber.i("AutoBackup: wrote %d events, pruned %d old files", events, removed)
        return AutoBackupOutcome.OK
    }

    private suspend fun record(outcome: AutoBackupOutcome, nowUtcMillis: Long) {
        settingsRepository.update { current ->
            current.copy(
                autoBackupLastRunAtUtcMillis = nowUtcMillis,
                autoBackupLastOutcome = outcome,
                // `lastBackupAtUtcMillis` drives the "your agenda changed since your last backup"
                // nudge, so only a run that actually produced a file may move it. Moving it on a
                // failure would silence the very prompt that is now the user's only warning.
                lastBackupAtUtcMillis = if (outcome == AutoBackupOutcome.OK) {
                    nowUtcMillis
                } else {
                    current.lastBackupAtUtcMillis
                },
            )
        }
    }

    companion object {
        /** How many automatic files the folder keeps. Four weekly ones ≈ a month of recovery points. */
        const val KEEP = 4

        /**
         * Marks the files this feature owns, and therefore the only ones [AutoBackupTarget.prune] may
         * delete. The user is expected to point the app at a folder they already use — very possibly
         * the one holding backups they exported by hand — and rotation must be unable to reach those.
         */
        const val AUTO_PREFIX = "agenda-tech-auto-"

        /**
         * Prefix of the half-written file. Deliberately outside [isAutomaticBackupFile] so rotation
         * treats it as none of its business, and it keeps the real extension so the SAF provider does
         * not append one of its own derived from the MIME type.
         */
        const val TEMP_PREFIX = "tmp-"

        fun fileNameFor(isoDate: String): String =
            "$AUTO_PREFIX$isoDate.${ExportBackupUseCase.FILE_EXTENSION}"

        fun tempNameFor(fileName: String): String = "$TEMP_PREFIX$fileName"

        /**
         * Whether a file in the user's folder is one this feature wrote, and therefore one rotation
         * may delete.
         *
         * Lives here, in the domain, rather than inside the SAF layer, because it is the single most
         * dangerous rule in the feature and the SAF layer cannot be unit-tested: the app is pointed at
         * a folder the user already uses, quite possibly the one holding the backups they exported by
         * hand, and this predicate is the only thing standing between rotation and those files.
         *
         * Matches the **exact** shape [fileNameFor] produces, anchored at both ends. A prefix-and-
         * extension check was not enough, and the gap was not theoretical: a file the user had named
         * `agenda-tech-auto-notes.atbak` matched it, and rotation would have deleted it. Signalled by
         * the gpt-5.2 review of 2026-08-31.
         */
        fun isAutomaticBackupFile(fileName: String): Boolean = AUTO_FILE_NAME.matches(fileName)

        /** A leftover from a run killed mid-write; safe to delete on sight, and never a real backup. */
        fun isAbandonedTempFile(fileName: String): Boolean =
            fileName.startsWith(TEMP_PREFIX) &&
                isAutomaticBackupFile(fileName.removePrefix(TEMP_PREFIX))

        private val AUTO_FILE_NAME = Regex(
            "^" + Regex.escape(AUTO_PREFIX) + "\\d{4}-\\d{2}-\\d{2}" +
                Regex.escape(".${ExportBackupUseCase.FILE_EXTENSION}") + "$",
        )
    }
}
