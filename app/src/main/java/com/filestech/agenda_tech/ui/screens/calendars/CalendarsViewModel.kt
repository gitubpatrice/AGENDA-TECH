package com.filestech.agenda_tech.ui.screens.calendars

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.core.result.Outcome
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.repository.EventRepository
import com.filestech.agenda_tech.domain.usecase.UpsertCalendarUseCase
import com.filestech.agenda_tech.system.AgendaChangeNotifier
import com.filestech.agenda_tech.system.alarm.ReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Ce que l'ecran Calendriers a tente d'ecrire, et qui a echoue. */
enum class CalendarsError { SAVE_FAILED, DELETE_FAILED }

data class CalendarsUiState(
    val calendars: List<Calendar> = emptyList(),
    val canDelete: Boolean = false,
    /** Vrai pendant une ecriture : les boutons qui ecrivent sont inertes tant qu'il l'est. */
    val busy: Boolean = false,
    val error: CalendarsError? = null,
)

@HiltViewModel
class CalendarsViewModel @Inject constructor(
    private val calendarRepository: CalendarRepository,
    private val upsertCalendar: UpsertCalendarUseCase,
    private val eventRepository: EventRepository,
    private val reminderScheduler: ReminderScheduler,
    private val agendaChanged: AgendaChangeNotifier,
) : ViewModel() {

    /** Ce que l'ecran ecrit lui-meme, tenu a part de ce que la base lui rend. */
    private data class Pending(val busy: Boolean = false, val error: CalendarsError? = null)

    /**
     * ⚠️ **Le garde anti-double-tap se lit ICI, jamais dans [uiState]**, et c'est tout l'interet de
     * cette separation.
     *
     * [uiState] est un `stateIn(WhileSubscribed)` : sans abonne il reste fige sur sa valeur initiale,
     * ou `busy` vaut `false`. Un garde qui s'y lirait serait donc inerte precisement dans les
     * situations que personne ne regarde. Ce piege a deja fait echouer la premiere version de
     * `CalendarsViewModelTest`, ou `delete()` sortait sur un `firstOrNull` toujours vide — il
     * transformerait ici un garde en decoration.
     */
    private val pending = MutableStateFlow(Pending())

    val uiState: StateFlow<CalendarsUiState> = combine(
        calendarRepository.observeAll(),
        pending,
    ) { calendars, local ->
        CalendarsUiState(
            calendars = calendars,
            canDelete = calendars.size > 1,
            busy = local.busy,
            error = local.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CalendarsUiState())

    fun setVisibility(id: Long, visible: Boolean) {
        viewModelScope.launch {
            calendarRepository.setVisibility(id, visible)
            // Le widget filtre sur la visibilite ; les rappels, non.
            agendaChanged.onAgendaChanged(rearmReminders = false)
        }
    }

    /** L'avis d'echec a ete lu : il ne doit pas reapparaitre a la rotation suivante. */
    fun consumeError() = pending.update { it.copy(error = null) }

    /**
     * Audit de coherence C3 — le garde anti-double-tap, sur le patron d'AG-3.
     *
     * Le risque n'etait pas cosmetique : `Calendar.id` vaut `0L` pour un calendrier neuf et
     * `CalendarDao.upsert` est un `@Upsert`, qui sur `id = 0` **insere**. `CalendarsScreen` fermait le
     * dialogue de facon synchrone dans le callback, mais la recomposition n'arrive qu'a l'image
     * suivante : deux tapes dans la meme image franchissaient toutes deux le callback et creaient
     * **deux calendriers identiques**. C'est la forme exacte du defaut corrige le meme jour sur
     * l'editeur d'evenement.
     *
     * Le garde est pose ici plutot que dans l'ecran parce que c'est la seule place ou il tienne :
     * `viewModelScope` depeche sur `Main.immediate`, donc `busy` est visible du second appel avant
     * tout point de suspension. Desactiver le bouton ne suffit pas — c'est ce que l'ecran faisait
     * deja, en fermant le dialogue.
     *
     * Audit de coherence C5 — l'echec est **dit**, plus seulement journalise. `UpsertCalendarUseCase`
     * documente rendre « a typed `AppError.Validation` the UI can surface directly » ; personne ne le
     * remontait, et le dialogue se refermait comme si l'ecriture avait eu lieu.
     */
    fun save(calendar: Calendar) {
        if (pending.value.busy) return
        pending.value = Pending(busy = true)
        viewModelScope.launch {
            try {
                when (val result = upsertCalendar(calendar)) {
                    is Outcome.Failure -> {
                        Timber.w("Calendar save rejected: %s", result.error)
                        pending.value = Pending(error = CalendarsError.SAVE_FAILED)
                    }
                    is Outcome.Success -> pending.value = Pending()
                }
            } catch (t: Throwable) {
                // `busy` RELACHE sur le chemin d'exception, sinon le drapeau pose contre le double-tap
                // devient un moyen de figer l'ecran pour de bon : plus aucun bouton ne repondrait
                // jusqu'a la destruction du ViewModel. C'est la lecon d'
                // `EventEditorViewModel.launchDeletion`, apprise la aussi apres coup.
                Timber.w(t, "Calendar save failed")
                pending.value = Pending(error = CalendarsError.SAVE_FAILED)
            }
        }
    }

    fun delete(id: Long) {
        if (pending.value.busy) return
        pending.value = Pending(busy = true)
        viewModelScope.launch {
            try {
                // Lu depuis le depot, pas depuis `uiState` : meme raison que le garde ci-dessus, la
                // valeur de `uiState` n'est vraie que tant qu'un abonne la maintient vivante.
                val calendars = calendarRepository.observeAll().first()
                // DIAL/ROB-1 — keep the "exactly one default calendar" invariant that deleteImported()
                // (DELETE WHERE is_default = 0) and new-event bootstrapping rely on: if the default is
                // being removed, promote another calendar to default. ROB-NEW-2 — done atomically.
                val target = calendars.firstOrNull { it.id == id }
                if (target == null) {
                    pending.value = Pending()
                    return@launch
                }
                val promoteId = if (target.isDefault) {
                    calendars.firstOrNull { it.id != id }?.id
                } else {
                    null
                }
                // Audit AG-9 — desarmer AVANT la cascade, et c'est tout l'enjeu.
                //
                // La suppression d'un calendrier efface ses evenements puis leurs rappels par cascade de
                // cle etrangere, sans qu'aucune alarme soit annulee. Isolees, ces orphelines sont
                // benignes (identifiants AUTOINCREMENT, donc getById rend null et rien n'est poste) —
                // mais une restauration reinsere les identifiants VERBATIM depuis le fichier, et
                // l'orpheline retrouve alors un AUTRE evenement portant son numero. Scenario F7.
                //
                // ⚠️ La premiere version de ce correctif se contentait d'appeler `onAgendaChanged()`.
                // Elle ne pouvait PAS fermer cette porte : `rescheduleAll()` itere
                // `reminderRepository.getAll()`, c'est-a-dire les rappels ENCORE EN BASE. Une ligne que
                // la cascade vient d'effacer n'y figure plus, donc rien ne la desarme — jamais, ni
                // plus tard. Le commentaire decrivait le scenario et l'appel ne le traitait pas.
                // Signale par la plongee securite du 2026-09-11.
                //
                // Enumerer AVANT la suppression est la seule fenetre ou c'est possible : apres,
                // `getForEvent` ne rend plus rien et l'alarme est hors d'atteinte pour de bon. Meme
                // patron que `EventEditorViewModel.deleteSeries`.
                eventRepository.observeByCalendar(id).first().forEach { event ->
                    reminderScheduler.cancelEvent(event.id)
                }
                calendarRepository.promoteDefaultAndDelete(promoteId, id)
                agendaChanged.onAgendaChanged()
                pending.value = Pending()
            } catch (t: Throwable) {
                // Une suppression qui echoue en silence laisse croire qu'elle a eu lieu : l'ecran
                // continuerait d'afficher le calendrier et l'utilisateur conclurait a un defaut
                // d'affichage, pas a une ecriture refusee.
                Timber.w(t, "Calendar deletion failed")
                pending.value = Pending(error = CalendarsError.DELETE_FAILED)
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
