package com.filestech.agenda_tech.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Immutable UI state for the home screen. Uses the [Calendar] domain model directly — no
 * separate UI DTO until a screen needs derived/formatted fields.
 */
data class HomeUiState(
    val calendars: List<Calendar> = emptyList(),
    val isLoading: Boolean = true,
)

/**
 * Backs the placeholder home screen. Observing [CalendarRepository] here exercises the full wiring
 * end-to-end (ViewModel → UseCase/Repository → DAO → SQLCipher-encrypted Room) so the graph is
 * proven from day one. Phase 2 replaces this with the month/week/day view-models.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    calendarRepository: CalendarRepository,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = calendarRepository.observeAll()
        .map { calendars -> HomeUiState(calendars = calendars, isLoading = false) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = HomeUiState(),
        )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
