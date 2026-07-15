package com.filestech.agenda_tech.ui.screens.backup

import android.content.Context
import android.net.Uri
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.core.crypto.wipe
import com.filestech.agenda_tech.core.result.AppError
import com.filestech.agenda_tech.core.result.Outcome
import com.filestech.agenda_tech.di.IoDispatcher
import com.filestech.agenda_tech.domain.repository.ReminderRepository
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
)

enum class BackupOp { EXPORT, RESTORE }

sealed interface BackupMessage {
    data class Exported(val events: Int) : BackupMessage
    data class Restored(val calendars: Int, val events: Int, val reminders: Int) : BackupMessage
    /** Wrong password *or* damaged file — the two are cryptographically indistinguishable. */
    data object BadPasswordOrFile : BackupMessage
    data object NotABackup : BackupMessage
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
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    fun suggestedFileName(): String = exportBackup.fileName(LocalDate.now().toString())

    fun export(uri: Uri, password: CharArray) = viewModelScope.launch {
        _state.update { it.copy(busy = BackupOp.EXPORT, message = null) }
        val message = when (val out = exportBackup(password, System.currentTimeMillis())) {
            is Outcome.Success -> writeFile(uri, out.value.bytes, out.value.events)
            is Outcome.Failure -> {
                Timber.w("Backup export failed: %s", out.error)
                if (out.error is AppError.Validation) BackupMessage.PasswordTooShort else BackupMessage.Failed
            }
        }
        _state.value = BackupUiState(busy = null, message = message)
    }

    fun restore(uri: Uri, password: CharArray) = viewModelScope.launch {
        _state.update { it.copy(busy = BackupOp.RESTORE, message = null) }
        val file = readFile(uri)
        if (file == null) {
            password.wipe()
            _state.value = BackupUiState(busy = null, message = BackupMessage.Failed)
            return@launch
        }

        if (!restoreBackup.isRecognised(file)) {
            password.wipe()
            _state.value = BackupUiState(busy = null, message = BackupMessage.NotABackup)
            return@launch
        }

        // Captured before the wipe: once the rows are gone their alarms can no longer be enumerated,
        // and a reminder from the replaced agenda would keep firing.
        val staleReminderIds = runCatching { reminderRepository.getAll().map { it.id } }.getOrDefault(emptyList())

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

    private suspend fun readFile(uri: Uri): ByteArray? = withContext(io) {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                // A backup of a personal agenda is kilobytes. The cap stops a wrong pick (a video,
                // a disk image) from being pulled into memory before it can be rejected.
                val bytes = input.readBytes()
                if (bytes.size > MAX_FILE_BYTES) null else bytes
            }
        } catch (t: Throwable) {
            Timber.w(t, "Backup restore: cannot read %s", uri)
            null
        }
    }

    private companion object {
        const val MAX_FILE_BYTES = 64 * 1024 * 1024
    }
}
