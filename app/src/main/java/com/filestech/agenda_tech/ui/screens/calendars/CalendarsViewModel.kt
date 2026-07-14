package com.filestech.agenda_tech.ui.screens.calendars

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.core.result.Outcome
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.usecase.UpsertCalendarUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
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
) : ViewModel() {

    val uiState: StateFlow<CalendarsUiState> = calendarRepository.observeAll()
        .map { calendars -> CalendarsUiState(calendars = calendars, canDelete = calendars.size > 1) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CalendarsUiState())

    fun setVisibility(id: Long, visible: Boolean) {
        viewModelScope.launch { calendarRepository.setVisibility(id, visible) }
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
            // being removed, promote another calendar to default first.
            val target = uiState.value.calendars.firstOrNull { it.id == id } ?: return@launch
            if (target.isDefault) {
                uiState.value.calendars.firstOrNull { it.id != id }?.let { replacement ->
                    calendarRepository.upsert(replacement.copy(isDefault = true))
                }
            }
            calendarRepository.delete(id)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
