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
import com.filestech.agenda_tech.core.di.IoDispatcher
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
import com.filestech.agenda_tech.data.backup.SafAutoBackupTarget
import com.filestech.agenda_tech.domain.backup.AutoBackupOutcome
import com.filestech.agenda_tech.domain.backup.AutoBackupSecret
import com.filestech.agenda_tech.domain.backup.AutoBackupTarget
import com.filestech.agenda_tech.domain.usecase.RunAutoBackupUseCase
import com.filestech.agenda_tech.system.backup.AutoBackupScheduler
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** What the screen is showing, one state at a time — the work is long enough to need a spinner. */
data class BackupUiState(
    /** Non-null while working; names the operation so the overlay can say what is actually happening. */
    val busy: BackupOp? = null,
    val message: BackupMessage? = null,
    /** True once a picked file has been recognised as an `.atbak` and only its password is missing. */
    val awaitingRestorePassword: Boolean = false,
    val autoBackupEnabled: Boolean = false,
    /** Display name of the chosen folder, or null when none is set or it can no longer be reached. */
    val autoBackupFolderName: String? = null,
    /** True when a folder is stored but no longer usable — the grant was revoked, or the card is out. */
    val autoBackupFolderLost: Boolean = false,
    val autoBackupLastRunAtUtcMillis: Long = 0L,
    val autoBackupLastOutcome: AutoBackupOutcome = AutoBackupOutcome.NEVER_RUN,
    /** Non-null while turning the feature on: what the screen still has to ask for. */
    val autoBackupStep: AutoBackupStep? = null,
)

/** The two things enabling automatic backups needs, asked one at a time. */
enum class AutoBackupStep { PICK_FOLDER, ASK_PASSWORD }

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
    private val autoBackupSecret: AutoBackupSecret,
    private val autoBackupTarget: AutoBackupTarget,
    private val autoBackupScheduler: AutoBackupScheduler,
    private val runAutoBackup: RunAutoBackupUseCase,
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

    /**
     * The export password, parked between the dialog that asks for it and the picker that names the
     * file. Held **here** and not in the composable, for the same reason [pendingRestoreFile] is.
     *
     * The picker is another activity, and the one that launched it can be recreated while it is on
     * screen — a rotation is enough. A `remember` in the screen loses the value at that moment, so
     * the picker came back, found nothing to encrypt with, and did nothing: the user named a file,
     * saw no error, and had no backup. The `CharArray` went with it, so it was never wiped either.
     *
     * The two halves of this screen were asymmetric — restore already parked its vetted file in the
     * ViewModel, export parked its password in the composition.
     */
    private var pendingExportPassword: CharArray? = null

    init {
        // The settings are the source of truth for this section, including for a run that happened
        // while the app was not open — which is the normal case for a weekly backup.
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                val folderName =
                    if (settings.autoBackupFolderUri != null) autoBackupTarget.folderName() else null
                _state.update {
                    it.copy(
                        autoBackupEnabled = settings.autoBackupEnabled,
                        autoBackupFolderName = folderName,
                        // A stored folder the app can no longer reach is the one failure the user can
                        // fix, and this screen is the only place they would ever learn of it.
                        autoBackupFolderLost = settings.autoBackupFolderUri != null && folderName == null,
                        autoBackupLastRunAtUtcMillis = settings.autoBackupLastRunAtUtcMillis,
                        autoBackupLastOutcome = settings.autoBackupLastOutcome,
                    )
                }
            }
        }
    }

    // --- Sauvegarde automatique ---------------------------------------------

    /**
     * Turning the switch on asks for whatever is still missing, one step at a time; turning it off
     * cancels the schedule and forgets the password.
     *
     * Off *deletes* the stored password rather than parking it: leaving a decryptable password behind
     * for a feature the user has just switched off is exactly the leftover the threat model in
     * [com.filestech.agenda_tech.domain.backup.AutoBackupSecret] exists to avoid. The folder grant is
     * kept — re-picking a folder is the tedious half, and a grant the user gave themselves is not a
     * secret.
     */
    fun onAutoBackupToggle(enabled: Boolean) = viewModelScope.launch {
        if (!enabled) {
            autoBackupScheduler.disable()
            autoBackupSecret.clear()
            settingsRepository.update { it.copy(autoBackupEnabled = false) }
            _state.update { it.copy(autoBackupStep = null) }
            return@launch
        }
        val step = when {
            !autoBackupTarget.isWritable() -> AutoBackupStep.PICK_FOLDER
            !autoBackupSecret.isSet() -> AutoBackupStep.ASK_PASSWORD
            else -> null
        }
        if (step == null) armAutoBackup() else _state.update { it.copy(autoBackupStep = step) }
    }

    /**
     * The folder came back from the system picker.
     *
     * The grant is persisted **before** the URI is stored: a URI in the settings the app holds no
     * lasting permission for would look like a configured backup and write nothing, every week, in
     * silence — the exact failure mode this whole feature exists to remove.
     */
    fun onAutoBackupFolderPicked(uri: Uri?) = viewModelScope.launch {
        if (uri == null) {
            _state.update { it.copy(autoBackupStep = null) }
            return@launch
        }
        val granted = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, SafAutoBackupTarget.PERSIST_FLAGS)
        }.isSuccess
        if (!granted) {
            Timber.e("AutoBackup: could not persist the folder grant")
            _state.update { it.copy(autoBackupStep = null, message = BackupMessage.Failed) }
            return@launch
        }
        settingsRepository.update { it.copy(autoBackupFolderUri = uri.toString()) }
        val next = if (autoBackupSecret.isSet()) null else AutoBackupStep.ASK_PASSWORD
        if (next == null) armAutoBackup() else _state.update { it.copy(autoBackupStep = next) }
    }

    fun onAutoBackupPasswordEntered(password: CharArray) = viewModelScope.launch {
        // The same floor the manual export enforces: one weak password would undo the 600 000 PBKDF2
        // iterations that protect the file.
        if (password.size < BackupEnvelope.MIN_PASSWORD_LENGTH) {
            password.wipe()
            _state.update { it.copy(autoBackupStep = null, message = BackupMessage.PasswordTooShort) }
            return@launch
        }
        // store() wipes the array, whether it succeeds or not.
        if (!autoBackupSecret.store(password)) {
            _state.update { it.copy(autoBackupStep = null, message = BackupMessage.Failed) }
            return@launch
        }
        armAutoBackup()
    }

    fun dismissAutoBackupStep() = _state.update { it.copy(autoBackupStep = null) }

    /**
     * Runs one backup immediately, so the user finds out today whether the folder and the password
     * actually work — rather than in a week, or on the day they need the file.
     */
    fun runAutoBackupNow() = viewModelScope.launch {
        _state.update { it.copy(busy = BackupOp.EXPORT) }
        runAutoBackup(nowUtcMillis = System.currentTimeMillis(), zone = ZoneId.systemDefault())
        // No snackbar: the outcome is recorded in the settings and the section already states it, so a
        // message would say the same thing twice and then disappear.
        _state.update { it.copy(busy = null) }
    }

    private suspend fun armAutoBackup() {
        settingsRepository.update { it.copy(autoBackupEnabled = true) }
        autoBackupScheduler.enable()
        _state.update { it.copy(autoBackupStep = null) }
    }

    /** Called when the export dialog is confirmed, just before the file picker is launched. */
    fun onExportPasswordEntered(password: CharArray) {
        pendingExportPassword?.wipe()
        pendingExportPassword = password
    }

    /** Called with the picker's answer; [uri] is null when the user backed out. */
    fun onExportTargetPicked(uri: Uri?) {
        val password = pendingExportPassword
        pendingExportPassword = null
        when {
            // Picker cancelled: the user chose not to export, so there is nothing to report.
            uri == null -> password?.wipe()
            // A file was named but the password is gone — the ViewModel was rebuilt from scratch
            // while the picker was up (process death). Moving the password here fixed the rotation
            // case; this is the half that survived it, and it failed the same silent way: the user
            // named a file, saw nothing, and had no backup. Said out loud now, like the restore
            // twin does in the same situation.
            password == null -> {
                Timber.w("Backup export: the password did not survive the picker — nothing was written")
                _state.update { it.copy(busy = null, message = BackupMessage.Failed) }
            }
            else -> export(uri, password)
        }
    }

    override fun onCleared() {
        // The screen can be left with a password parked and no picker result coming.
        pendingExportPassword?.wipe()
        pendingExportPassword = null
        pendingRestoreFile = null
        super.onCleared()
    }

    fun export(uri: Uri, password: CharArray) = viewModelScope.launch {
        // The wipe is guaranteed HERE, not only inside the use case — found by both reviews of this
        // lot. `ExportBackupUseCase` scrubs the array on every path it reaches, but this coroutine can
        // be cancelled before it reaches any of them (leaving the screen right after confirming), and
        // then the plaintext password stayed in the heap, held by a dead closure, with nothing left
        // holding a reference that could clear it. Wiping twice is free: the second pass overwrites
        // spaces with spaces.
        try {
            exportInto(uri, password)
        } finally {
            password.wipe()
        }
    }

    private suspend fun exportInto(uri: Uri, password: CharArray) {
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
        // Audit AG-12 — `_state.value = BackupUiState(...)` remettait AUSSI la section
        // « sauvegarde automatique » a ses valeurs par defaut (interrupteur off, dossier nul,
        // dernier resultat NEVER_RUN). Or son seul repeupleur est le collecteur de reglages, et
        // `dataStore.data` ne reemet QUE sur ecriture : la section disparaissait donc de l'ecran
        // — en emportant `autoBackupLastOutcome`, le seul avertissement que l'utilisateur recoit
        // quand une sauvegarde hebdomadaire echoue — alors que le travail restait planifie.
        // `update { copy }` ne touche que ce que cette operation concerne.
        _state.update { it.copy(busy = null, message = message) }
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
        // A second tap on the confirm button, while the first restore is still running.
        //
        // Without this it fell through to the "no file in hand" branch below — because the first call
        // had already taken `pendingRestoreFile` — and published `Failed`. That cleared `busy`, so the
        // overlay vanished and the user was told the restore had failed **while it was replacing
        // their agenda**. The one message that must never be wrong on this screen, on the one
        // operation that cannot be undone.
        //
        // Safe as a plain read: `viewModelScope` dispatches on `Main.immediate`, and `busy` is set
        // below before the first suspension point, so a second tap cannot interleave ahead of it.
        if (_state.value.busy != null) {
            password.wipe()
            return@launch
        }
        val file = pendingRestoreFile
        if (file == null) {
            // No file in hand (process death between the pick and the password): nothing to restore.
            password.wipe()
            // `awaitingRestorePassword` est remis à false EXPLICITEMENT : ce chemin est le seul des
            // cinq d'AG-12 à s'exécuter alors que le dialogue de mot de passe est encore affiché, et
            // c'est le remplacement intégral de l'état qui le refermait jusqu'ici. Le `copy` ne
            // referme que ce qu'on lui nomme — l'oublier laisserait l'utilisateur devant un dialogue
            // qui ne mène plus nulle part.
            _state.update {
                it.copy(busy = null, awaitingRestorePassword = false, message = BackupMessage.Failed)
            }
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
                _state.update { it.copy(busy = null, message = BackupMessage.Failed) }
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
        _state.update { it.copy(busy = null, message = message) }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /**
     * Écrit la sauvegarde à l'emplacement choisi, **sans détruire celle qui s'y trouvait** si
     * l'écriture échoue (audit AG-6).
     *
     * ## Ce que le mode `"wt"` coûtait
     *
     * `"wt"` tronque le fichier AVANT d'écrire, et le commentaire d'origine disait déjà pourquoi il
     * est nécessaire : `CreateDocument` rend le fichier EXISTANT quand l'utilisateur écrase, et sans
     * troncature une sauvegarde plus courte laisserait la queue de l'ancienne collée à sa suite.
     *
     * Le prix n'était pas dit : entre la troncature et la fin de l'écriture, l'ancienne sauvegarde
     * n'existe plus et la nouvelle n'existe pas encore. Une carte retirée, une autorisation révoquée,
     * un fournisseur en erreur à cet instant, et l'utilisateur n'a **plus rien** — avec
     * `allowBackup="false"`, sur ce qui est possiblement sa seule copie. L'échec lui était bien
     * signalé, ce qui rend le défaut moins grave qu'une perte silencieuse, mais pas moins définitif.
     *
     * Le jumeau automatique n'a pas ce problème : [com.filestech.agenda_tech.data.backup.SafAutoBackupTarget]
     * écrit dans un `tmp-…` puis bascule, correctif posé le 2026-08-31 et jamais reporté ici.
     *
     * ## Pourquoi pas le même correctif
     *
     * Il n'est pas transposable : le jumeau détient une URI d'ARBORESCENCE, donc il peut créer un
     * fichier voisin. Ici on ne tient qu'une URI de DOCUMENT — le sélecteur ne donne aucun droit sur
     * le dossier qui le contient, et rien ne permet d'y déposer un temporaire.
     *
     * Ce qui reste faisable, et qui est fait : relire l'ancien contenu avant de tronquer, et le
     * réécrire si l'écriture échoue. Ça couvre les pannes de fournisseur, les révocations et les
     * erreurs de flux — pas un disque plein, où la réécriture échouera aussi. C'est une amélioration
     * franche, pas une garantie, et la différence est dite plutôt que sous-entendue.
     */
    private suspend fun writeFile(uri: Uri, bytes: ByteArray, events: Int): BackupMessage = withContext(io) {
        // Lu AVANT la troncature, et borné par le même plafond que la restauration : un fournisseur
        // hostile ne doit pas pouvoir faire tenir un fichier arbitraire en mémoire.
        val previous = runCatching {
            context.contentResolver.openInputStream(uri)?.use { BoundedRead.readAtMost(it, MAX_FILE_BYTES) }
        }.getOrNull()

        // Vrai UNIQUEMENT si les octets sont tous partis. Un DocumentProvider peut accepter chaque
        // `write()` puis lever au `close()` : sans ce drapeau, le filet se declenchait alors que le
        // NOUVEAU fichier etait deja correctement ecrit, et le remplacait par l'ancien — il detruisait
        // la sauvegarde qu'il etait cense proteger. Signale par la relecture gpt-5.2 du 2026-09-11.
        var bytesFullyWritten = false
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(bytes)
                it.flush()
                bytesFullyWritten = true
            } ?: return@withContext BackupMessage.Failed
            BackupMessage.Exported(events = events)
        } catch (t: Throwable) {
            Timber.w(t, "Backup export: cannot write to %s", uri)
            if (previous != null && !bytesFullyWritten) {
                // Best-effort, et journalisé des deux côtés : savoir qu'une remise en place a échoué
                // vaut mieux que de croire l'ancienne sauvegarde intacte.
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(previous) }
                }
                    .onSuccess { Timber.i("Backup export: previous file restored after a failed write") }
                    .onFailure { Timber.w(it, "Backup export: could NOT restore the previous file") }
            }
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
