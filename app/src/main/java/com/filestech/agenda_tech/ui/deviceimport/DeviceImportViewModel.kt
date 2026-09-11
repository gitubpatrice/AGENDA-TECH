package com.filestech.agenda_tech.ui.deviceimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.domain.model.DeviceCalendar
import com.filestech.agenda_tech.domain.usecase.ImportDeviceEventsUseCase
import com.filestech.agenda_tech.system.AgendaChangeNotifier
import com.filestech.agenda_tech.system.alarm.ReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Drives the "import from the device calendar" screen: permission → list → select → import. */
@HiltViewModel
class DeviceImportViewModel @Inject constructor(
    private val importDeviceEvents: ImportDeviceEventsUseCase,
    private val reminderScheduler: ReminderScheduler,
    private val agendaChanged: AgendaChangeNotifier,
) : ViewModel() {

    sealed interface UiState {
        /** Waiting for the user to grant READ_CALENDAR. */
        data object NeedPermission : UiState
        data object Loading : UiState
        data class Ready(val calendars: List<DeviceCalendar>) : UiState

        /** Permission granted but the device exposes no calendars. */
        data object Empty : UiState

        /**
         * La liste n'a pas pu etre lue (audit pre-release v1.1.0).
         *
         * `listCalendars()` interroge le fournisseur de calendriers du systeme. Il peut lever : une
         * permission revoquee depuis les Reglages entre l'octroi et la requete donne une
         * `SecurityException`, et un fournisseur tiers qui meurt donne une `DeadObjectException`.
         * L'appel n'etait garde par rien : l'exception traversait `viewModelScope.launch`, ou aucun
         * `CoroutineExceptionHandler` ne l'attend.
         */
        data object Failed : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.NeedPermission)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _result = MutableStateFlow<ImportDeviceEventsUseCase.Result?>(null)
    val result: StateFlow<ImportDeviceEventsUseCase.Result?> = _result.asStateFlow()

    private val _cleared = MutableStateFlow(false)
    val cleared: StateFlow<Boolean> = _cleared.asStateFlow()

    /** Un import ou un effacement a echoue : signal a usage unique, consomme par l'ecran. */
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    fun onPermissionGranted() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                val calendars = importDeviceEvents.listCalendars()
                _state.value = if (calendars.isEmpty()) UiState.Empty else UiState.Ready(calendars)
                // Pre-select everything: the common case is "import all my calendars".
                _selected.value = calendars.map { it.id }.toSet()
            } catch (t: Throwable) {
                // Seulement le TYPE, jamais le message : une erreur de fournisseur cite volontiers
                // la requete, donc le contenu de l'agenda de l'utilisateur.
                Timber.w("Device calendar listing failed (%s)", t.javaClass.simpleName)
                _state.value = UiState.Failed
            }
        }
    }

    fun onPermissionDenied() {
        _state.value = UiState.NeedPermission
    }

    fun toggle(calendarId: Long) {
        _selected.value = _selected.value.toMutableSet().apply {
            if (!add(calendarId)) remove(calendarId)
        }
    }

    fun import(fallbackCalendarName: String) {
        val ids = _selected.value.toList()
        if (ids.isEmpty()) return
        launchImporting {
            _result.value = importDeviceEvents(ids, fallbackCalendarName)
            // Audit AG-8 — meme raison que l'import .ics : un evenement deplace dans l'agenda
            // source puis re-importe gardait son alarme a l'ancienne heure.
            agendaChanged.onAgendaChanged()
        }
    }

    fun consumeResult() {
        _result.value = null
    }

    /** Wipes previously imported calendars/events (fixes legacy duplicates) before a clean re-import. */
    fun clearImported() = launchImporting {
        // Audit AG-9 — desarmer AVANT la cascade : apres, les rappels n'existent plus en base
        // et `rescheduleAll()`, qui ne parcourt que ce qui existe, ne peut plus rien annuler.
        // (La premiere version de ce correctif appelait seulement `onAgendaChanged()`, ce qui
        // ne fermait pas la porte — plongee securite du 2026-09-11.)
        importDeviceEvents.importedEventIds().forEach { reminderScheduler.cancelEvent(it) }
        importDeviceEvents.clearImported()
        agendaChanged.onAgendaChanged()
        _cleared.value = true
    }

    /**
     * L'enveloppe commune aux deux ecritures de cet ecran — audit pre-release v1.1.0.
     *
     * ## Ce qu'elle rattrape
     *
     * `import()` et `clearImported()` posaient `_importing` a `true` puis lancaient leur corps **sans
     * `try/finally`**. Or `clearImported()` traverse `importedEventIds()` (deux lectures Room) puis
     * `deleteImported()` (un DELETE SQLCipher brut), et **aucun des deux n'est garde ou que ce soit**
     * dans la chaine : le `runCatching` du use case ne couvre que la boucle par calendrier de
     * l'import. Une exception traversait donc `viewModelScope.launch`, ou aucun
     * `CoroutineExceptionHandler` ne l'attend.
     *
     * ## Pourquoi c'etait pire qu'un drapeau coince
     *
     * `DeviceImportScreen` avale le geste de retour par `BackHandler(enabled = importing)` et
     * desactive le bouton retour pendant l'import — a raison, pour qu'on ne detruise pas le ViewModel
     * en vol. Mais `_importing` reste alors a `true` pour toujours : **l'utilisateur ne peut plus
     * quitter l'ecran du tout**, ni reessayer. Ce n'est pas un drapeau bloque, c'est un cul-de-sac.
     *
     * ## Le motif, pour la sixieme fois en une journee
     *
     * Le garde `busy` avait ete pose le meme jour sur `EventEditorViewModel.launchDeletion`,
     * `CalendarsViewModel` et `IcsViewModel.launchBusy` — **et pas ici**, alors que ce meme jour a
     * ajoute du code neuf a l'interieur de ces deux blocs (`onAgendaChanged()` et le desarmement
     * AG-9). Le chemin jumeau, cree par la correction meme qui traquait le chemin jumeau. Trouve par
     * l'audit pre-release, pas par sa propre relecture.
     */
    private fun launchImporting(block: suspend () -> Unit) {
        if (_importing.value) return
        _importing.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (t: Throwable) {
                // Seulement le TYPE : une erreur SQLCipher ou de fournisseur cite volontiers la
                // requete, donc le contenu de l'agenda.
                Timber.w("Device import step failed (%s)", t.javaClass.simpleName)
                _failed.value = true
            } finally {
                // `finally` et pas une ligne apres le corps : une annulation doit relacher le drapeau
                // elle aussi, sinon le ViewModel survit a la rotation avec l'ecran verrouille.
                _importing.value = false
            }
        }
    }

    /** L'avis d'echec a ete montre. */
    fun consumeFailed() {
        _failed.value = false
    }

    fun consumeCleared() {
        _cleared.value = false
    }
}
