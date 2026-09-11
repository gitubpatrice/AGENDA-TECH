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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class CalendarsUiState(
    val calendars: List<Calendar> = emptyList(),
    val canDelete: Boolean = false,
)

@HiltViewModel
class CalendarsViewModel @Inject constructor(
    private val calendarRepository: CalendarRepository,
    private val upsertCalendar: UpsertCalendarUseCase,
    private val eventRepository: EventRepository,
    private val reminderScheduler: ReminderScheduler,
    private val agendaChanged: AgendaChangeNotifier,
) : ViewModel() {

    val uiState: StateFlow<CalendarsUiState> = calendarRepository.observeAll()
        .map { calendars -> CalendarsUiState(calendars = calendars, canDelete = calendars.size > 1) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CalendarsUiState())

    fun setVisibility(id: Long, visible: Boolean) {
        viewModelScope.launch {
            calendarRepository.setVisibility(id, visible)
            // Le widget filtre sur la visibilite ; les rappels, non.
            agendaChanged.onAgendaChanged(rearmReminders = false)
        }
    }

    fun save(calendar: Calendar) {
        // UpsertCalendarUseCase trims + rejects blank names; the dialog also disables Save when blank.
        viewModelScope.launch {
            when (val result = upsertCalendar(calendar)) {
                is Outcome.Failure -> Timber.w("Calendar save rejected: %s", result.error)
                is Outcome.Success -> Unit
            }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            // DIAL/ROB-1 — keep the "exactly one default calendar" invariant that deleteImported()
            // (DELETE WHERE is_default = 0) and new-event bootstrapping rely on: if the default is
            // being removed, promote another calendar to default. ROB-NEW-2 — done atomically.
            val target = uiState.value.calendars.firstOrNull { it.id == id } ?: return@launch
            val promoteId = if (target.isDefault) {
                uiState.value.calendars.firstOrNull { it.id != id }?.id
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
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
