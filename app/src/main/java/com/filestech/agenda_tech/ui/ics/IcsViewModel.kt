package com.filestech.agenda_tech.ui.ics

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.core.io.BoundedRead
import com.filestech.agenda_tech.core.di.IoDispatcher
import com.filestech.agenda_tech.domain.usecase.ExportEventsUseCase
import com.filestech.agenda_tech.domain.usecase.ImportEventsUseCase
import com.filestech.agenda_tech.system.AgendaChangeNotifier
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.ZoneId
import javax.inject.Inject

/** One-shot result of an import/export, consumed by the screen to show a message. */
sealed interface IcsResult {
    data class Exported(val count: Int) : IcsResult
    data class Imported(val count: Int) : IcsResult
    data object Failed : IcsResult

    /**
     * The file was readable but holds more events than the app accepts (audit S12).
     *
     * Kept apart from [Failed] on purpose: it is the one import refusal the user can act on — split
     * the file, or pick another — and "import failed" would tell them nothing.
     */
    data object TooManyEvents : IcsResult
}

/**
 * Drives `.ics` import/export against a user-picked document [Uri]. All file I/O runs off the main
 * thread; failures are logged (never crash) and surfaced as [IcsResult.Failed].
 */
@HiltViewModel
class IcsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exportEvents: ExportEventsUseCase,
    private val importEvents: ImportEventsUseCase,
    private val agendaChanged: AgendaChangeNotifier,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val _result = MutableStateFlow<IcsResult?>(null)
    val result: StateFlow<IcsResult?> = _result.asStateFlow()

    /**
     * Vrai pendant un import ou un export (audit de coherence C4).
     *
     * Deux roles, d'inegale importance, et il vaut de les distinguer parce que le rapport d'audit
     * initial les confondait :
     *
     * 1. **Dire que quelque chose se passe.** C'est le vrai manque. Le plafond d'import est de 5 Mo
     *    et le fichier est lu, analyse, puis ecrit ligne par ligne : sur un gros agenda l'ecran
     *    restait parfaitement immobile, sans aucun signe, jusqu'au message final.
     * 2. **Empecher un second lancement.** Marginal ici, et le rapport le surestimait : on n'entre
     *    dans [import] et [export] que par le selecteur de documents du systeme, qui est une autre
     *    activite — le menu est deja referme quand il s'ouvre, et il ne rend qu'un `Uri` a la fois.
     *    Les *donnees*, elles, sont deja protegees par le `Mutex` d'`ImportEventsUseCase`. Le garde
     *    est pose quand meme, parce qu'un etat qui dit « occupe » et laisse passer une seconde
     *    ecriture est pire qu'aucun etat : il se lit comme une garantie.
     */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun export(uri: Uri) = launchBusy {
        val outcome = withContext(io) {
            runCatching {
                val exported = exportEvents(System.currentTimeMillis())
                // Audit AG-7 — "wt" TRONQUE, et son absence était le jumeau non corrigé de l'export
                // de sauvegarde (BackupViewModel.writeFile, qui porte la même note depuis plus
                // longtemps). Le sélecteur CreateDocument rend le fichier EXISTANT quand l'utilisateur
                // écrase : sans troncature, réexporter par-dessus un .ics plus gros laissait la queue
                // de l'ancien collée après END:VCALENDAR. Un lecteur tolérant y importe alors un
                // événement fantôme — et un lecteur strict refuse le fichier entier.
                context.contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(exported.ics.toByteArray(Charsets.UTF_8))
                } ?: error("no output stream for $uri")
                exported.eventCount
            }
        }
        _result.value = outcome.fold(
            onSuccess = { IcsResult.Exported(it) },
            // Only the failure TYPE, never the throwable: the same rule NoOpReleaseTree sets and that
            // the backup path already follows. What is being written here is the user's whole agenda,
            // and a serialization or SQLite error quotes the offending input in its message.
            onFailure = { Timber.w("ICS export failed (%s)", it.javaClass.simpleName); IcsResult.Failed },
        )
    }

    /**
     * Audit F4 — the ceiling now bounds the read itself ([BoundedRead]), instead of trusting the size
     * the document provider declares. Asking first failed three ways: `openFileDescriptor` returning
     * null skipped the check and read whatever came, a provider reporting a small size then serving
     * gigabytes passed it, and a provider that honestly does not know reports `-1`, which the check
     * refused — turning a legitimate file into "import failed". One correction covers all three,
     * because none of them is about the size: they are about who is asked.
     */
    fun import(uri: Uri) = launchBusy {
        val outcome = withContext(io) {
            runCatching {
                val stream = context.contentResolver.openInputStream(uri) ?: error("no input stream")
                // Two distinct refusals, kept distinct: an unreadable pick and a file over the ceiling
                // are different things to diagnose, and folding them into one elvis would have made
                // "too large" report itself as "no input stream".
                val bytes = stream.use { BoundedRead.readAtMost(it, MAX_ICS_BYTES) }
                    ?: error("ics file exceeds $MAX_ICS_BYTES bytes")
                importEvents(bytes.toString(Charsets.UTF_8), ZoneId.systemDefault().id)
            }
        }
        // Audit AG-8 — un evenement deja importe, deplace dans l'agenda source puis re-importe,
        // gardait son alarme a l'ANCIENNE heure : aucun des deux imports n'appelait le
        // planificateur. Et le widget ne voyait rien des lignes neuves pendant une demi-heure.
        if (outcome.isSuccess) agendaChanged.onAgendaChanged()
        _result.value = outcome.fold(
            onSuccess = { IcsResult.Imported(it) },
            onFailure = { error ->
                Timber.w("ICS import failed (%s)", error.javaClass.simpleName)
                if (error is ImportEventsUseCase.TooManyEvents) IcsResult.TooManyEvents else IcsResult.Failed
            },
        )
    }

    /**
     * Une seule enveloppe pour les deux chemins, plutot que deux `try/finally` jumeaux.
     *
     * C'est le motif de la journee pris a l'envers : deux copies du meme geste, c'est deux occasions
     * d'en durcir une et d'oublier l'autre. Ici la copie oubliee aurait ete l'export.
     *
     * `finally` et non une ligne posee apres le corps : une annulation — l'utilisateur quitte l'ecran
     * pendant l'import — laisserait sinon `busy` a `true` sur un ViewModel qui, lui, survit a la
     * rotation, et plus rien ne s'importerait jusqu'a sa destruction. Meme lecon que
     * `EventEditorViewModel.launchDeletion`, ou le drapeau pose contre le double-tap etait devenu un
     * moyen de figer l'ecran pour de bon.
     */
    private fun launchBusy(block: suspend () -> Unit) = viewModelScope.launch {
        if (_busy.value) return@launch
        _busy.value = true
        try {
            block()
        } finally {
            _busy.value = false
        }
    }

    fun consumeResult() {
        _result.value = null
    }

    private companion object {
        // A personal agenda export is a few KB; 5 MB is a generous ceiling that blocks a DoS file.
        const val MAX_ICS_BYTES = 5L * 1024 * 1024
    }
}
