package com.filestech.agenda_tech.ui.screens.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.usecase.ObserveOccurrencesInRangeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class DayUiState(val day: DayTimelineData)

@HiltViewModel
class DayViewModel @Inject constructor(
    private val observeOccurrences: ObserveOccurrencesInRangeUseCase,
    calendarRepository: CalendarRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val displayedDate = MutableStateFlow(LocalDate.now(zone))

    @OptIn(ExperimentalCoroutinesApi::class)
    private val occurrences = displayedDate.flatMapLatest { date ->
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        observeOccurrences(start, end)
    }

    val uiState: StateFlow<DayUiState> = combine(
        displayedDate,
        occurrences,
        calendarRepository.observeAll(),
    ) { date, occ, calendars ->
        val items = occ.toTimelineItems(calendars.associate { it.id to it.color.argb })
        DayUiState(TimelineBuilder.build(items, date, zone, LocalDate.now(zone), System.currentTimeMillis()))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = DayUiState(
            TimelineBuilder.build(emptyList(), LocalDate.now(zone), zone, LocalDate.now(zone), System.currentTimeMillis()),
        ),
    )

    val displayedDateValue: LocalDate get() = displayedDate.value

    fun onPreviousDay() { displayedDate.value = displayedDate.value.minusDays(1) }
    fun onNextDay() { displayedDate.value = displayedDate.value.plusDays(1) }
    fun onToday() { displayedDate.value = LocalDate.now(zone) }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
