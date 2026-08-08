package com.filestech.agenda_tech.ui.screens.backup

import android.content.Context
import android.net.Uri
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.core.crypto.BackupEnvelope
import com.filestech.agenda_tech.core.crypto.wipe
import com.filestech.agenda_tech.core.result.AppError
import com.filestech.agenda_tech.core.io.BoundedRead
import com.filestech.agenda_tech.core.result.Outcome
import com.filestech.agenda_tech.di.IoDispatcher
import com.filestech.agenda_tech.domain.repository.ReminderRepository
import com.filestech.agenda_tech.domain.repository.SettingsRepository
import com.filestech.agenda_tech.domain.usecase.ExportBackupUseCase
import com.filestech.agenda_tech.domain.usecase.RestoreBackupUseCase
import com.filestech.agenda_tech.system.alarm.ReminderScheduler
import com.filestech.agenda_tech.widget.AgendaWidget
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject

/** What the screen is showing, one state at a time — the work is long enough to need a spinner. */
data class BackupUiState(
    /** Non-null while working; names the operation so the overlay can say what is actually happening. */
    val busy: BackupOp? = null,
    val message: BackupMessage? = null,
    /** True once a picked file has been recognised as an `.atbak` and only its password is missing. */
    val awaitingRestorePassword: Boolean = false,
)

enum class BackupOp { EXPORT, RESTORE }

sealed interface BackupMessage {
    data class Exported(val events: Int) : BackupMessage
    data class Restored(val calendars: Int, val events: Int, val reminders: Int) : BackupMessage
    /** Wrong password *or* damaged file — the two are cryptographically indistinguishable. */
    data object BadPasswordOrFile : BackupMessage
    data object NotABackup : BackupMessage

    /** A real `.atbak`, written by a newer Agenda Tech than this one. The app is what must change. */
    data object BackupTooNew : BackupMessage

    /** A real `.atbak` whose header is truncated or out of range. Says so, rather than disowning it. */
    data object BackupDamaged : BackupMessage
    data object PasswordTooShort : BackupMessage
    data object Failed : BackupMessage
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exportBackup: ExportBackupUseCase,
    private val restoreBackup: RestoreBackupUseCase,
    private val reminderRepository: ReminderRepository,
    private val reminderScheduler: ReminderScheduler,
    private val settingsRepository: SettingsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    /**
     * The picked file, held between "recognised as a backup" and "password entered". Kept here rather
     * than re-read on confirm so the bytes that were vetted are the bytes that get decrypted; it is
     * ciphertext, so holding it is not itself a disclosure.
     */
    private var pendingRestoreFile: ByteArray? = null

    fun suggestedFileName(): String = exportBackup.fileName(LocalDate.now().toString())

    fun export(uri: Uri, password: CharArray) = viewModelScope.launch {
        _state.update { it.copy(busy = BackupOp.EXPORT, message = null) }
        val message = when (val out = exportBackup(password, System.currentTimeMillis())) {
            is Outcome.Success -> writeFile(uri, out.value.bytes, out.value.events).also { result ->
                // Recorded only once the bytes are actually on disk. Stamping it on a failed write
                // would silence the backup reminder about a backup that does not exist — the one
                // outcome worse than not reminding at all.
                if (result is BackupMessage.Exported) {
                    settingsRepository.update { it.copy(lastBackupAtUtcMillis = System.currentTimeMillis()) }
                }
            }
            is Outcome.Failure -> {
                Timber.w("Backup export failed: %s", out.error)
                if (out.error is AppError.Validation) BackupMessage.PasswordTooShort else BackupMessage.Failed
            }
        }
        _state.value = BackupUiState(busy = null, message = message)
    }

    /**
     * Reads the picked file and decides what it is, **before** the password is asked for. A wrong
     * pick (a photo, a PDF) is answered on its magic bytes alone, so the user is never made to type
     * a password only to be told the file was never openable.
     *
     * Safe to answer honestly: everything here is decided before any key is derived, so none of the
     * four answers reveals anything about the password.
     *
     * The three refusals are kept distinct because they ask for three different things. Two of them
     * are about a file that **is** a backup — one this build is too old to read, or one that arrived
     * damaged — and both used to be answered "this is not an Agenda Tech backup". With
     * `allowBackup=false` that `.atbak` may be the only copy of the agenda in existence, so a
     * refusal phrased as a disowning is an instruction to delete it.
     */
    fun onRestoreFilePicked(uri: Uri) = viewModelScope.launch {
        val file = readFile(uri)
        _state.value = if (file == null) {
            BackupUiState(message = BackupMessage.Failed)
        } else {
            when (restoreBackup.recognise(file)) {
                is BackupEnvelope.Recognition.Openable -> {
                    pendingRestoreFile = file
                    BackupUiState(awaitingRestorePassword = true)
                }
                BackupEnvelope.Recognition.UnsupportedVersion ->
                    BackupUiState(message = BackupMessage.BackupTooNew)
                BackupEnvelope.Recognition.Malformed ->
                    BackupUiState(message = BackupMessage.BackupDamaged)
                BackupEnvelope.Recognition.NotABackup ->
                    BackupUiState(message = BackupMessage.NotABackup)
            }
        }
    }

    /** The user backed out of the password dialog — drop the file we were holding. */
    fun cancelRestore() {
        pendingRestoreFile = null
        _state.update { it.copy(awaitingRestorePassword = false) }
    }

    fun restore(password: CharArray) = viewModelScope.launch {
        val file = pendingRestoreFile
        if (file == null) {
            // No file in hand (process death between the pick and the password): nothing to restore.
            password.wipe()
            _state.value = BackupUiState(message = BackupMessage.Failed)
            return@launch
        }
        pendingRestoreFile = null
        _state.update { it.copy(busy = BackupOp.RESTORE, awaitingRestorePassword = false, message = null) }

        // Captured before the wipe: once the rows are gone their alarms can no longer be enumerated,
        // and a reminder from the replaced agenda would keep firing.
        //
        // Audit F7 — this used to end in `.getOrDefault(emptyList())`, which defeated the very purpose of
        // the line it was attached to: on failure the restore went ahead, `cancelReminders([])` disarmed
        // nothing, and the old agenda's alarms stayed armed. They are not harmless. A restore reinserts
        // event ids **verbatim** from the file, so a stale alarm carrying `eventId = 42` finds a *different*
        // event 42 in the restored agenda: `ReminderActionHandler` looks it up, finds it, and posts a
        // reminder for the WRONG event at the OLD time, then reschedules it.
        //
        // So it fails closed, like the rest of this path — `RestoreBackupUseCase` already refuses a file
        // whole rather than applying half of it. Nothing has been written yet at this point, so refusing
        // costs the user a retry and never an inconsistent agenda.
        val staleReminderIds = runCatching { reminderRepository.getAll().map { it.id } }
            .getOrElse { error ->
                Timber.w(error, "Backup restore: cannot enumerate the alarms to disarm — refusing")
                _state.value = BackupUiState(busy = null, message = BackupMessage.Failed)
                password.wipe()
                return@launch
            }

        val message = when (val out = restoreBackup(password, file)) {
            is Outcome.Success -> {
                reminderScheduler.cancelReminders(staleReminderIds)
                reminderScheduler.rescheduleAll()
                // The widget renders a one-shot snapshot, so after replacing the whole agenda it
                // would keep showing events that no longer exist until its next update cycle.
                runCatching { AgendaWidget().updateAll(context) }
                    .onFailure { Timber.w(it, "Backup restore: widget refresh failed") }
                BackupMessage.Restored(out.value.calendars, out.value.events, out.value.reminders)
            }
            is Outcome.Failure -> {
                Timber.w("Backup restore failed: %s", out.error)
                // The file is a real .atbak (checked above), so anything left is a GCM tag mismatch
                // or unreadable contents — a wrong password and a damaged file are indistinguishable.
                when (out.error) {
                    is AppError.Crypto, is AppError.Validation -> BackupMessage.BadPasswordOrFile
                    else -> BackupMessage.Failed
                }
            }
        }
        _state.value = BackupUiState(busy = null, message = message)
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private suspend fun writeFile(uri: Uri, bytes: ByteArray, events: Int): BackupMessage = withContext(io) {
        try {
            // "wt" truncates: SAF hands back the existing file when the user overwrites one, and
            // without truncation a shorter backup would keep the old file's tail glued to its end.
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: return@withContext BackupMessage.Failed
            BackupMessage.Exported(events = events)
        } catch (t: Throwable) {
            Timber.w(t, "Backup export: cannot write to %s", uri)
            BackupMessage.Failed
        }
    }

    // Reads the picked file, refusing anything implausibly large. The bounded read itself lives in
    // BoundedRead, shared with the .ics importer: the two pickers face the same untrusted input and
    // had grown two answers to it, of which only this one was correct (audit F4).
    //
    // Line comments, not KDoc: a wildcard MIME type carries the sequence that ends a block comment.
    private suspend fun readFile(uri: Uri): ByteArray? = withContext(io) {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BoundedRead.readAtMost(input, MAX_FILE_BYTES).also {
                    if (it == null) {
                        Timber.w("Backup restore: file exceeds %d bytes — stopped reading", MAX_FILE_BYTES)
                    }
                }
            }
        } catch (t: Throwable) {
            Timber.w(t, "Backup restore: cannot read %s", uri)
            null
        }
    }

    private companion object {
        /**
         * A personal agenda's backup is kilobytes; megabytes would already be extraordinary. Generous
         * enough to never refuse a real file, small enough that a mis-picked video is refused rather
         * than loaded.
         */
        const val MAX_FILE_BYTES = 16L * 1024 * 1024
    }
}
