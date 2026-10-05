package com.filestech.agenda_tech.ui.screens.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.core.time.DeviceZone
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.usecase.ObserveOccurrencesInRangeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class DayUiState(val day: DayTimelineData)

@HiltViewModel
class DayViewModel @Inject constructor(
    private val observeOccurrences: ObserveOccurrencesInRangeUseCase,
    calendarRepository: CalendarRepository,
    private val deviceZone: DeviceZone,
) : ViewModel() {

    /**
     * The phone's zone now; the query below follows [DeviceZone.zone]. Taken once at construction, an
     * open screen kept the zone it was opened in after a journey.
     */
    private val zone: ZoneId get() = deviceZone.zone.value
    private val displayedDate = MutableStateFlow(LocalDate.now(zone))

    @OptIn(ExperimentalCoroutinesApi::class)
    private val occurrences = combine(displayedDate, deviceZone.zone) { date, now -> date to now }
        .flatMapLatest { (date, dayZone) ->
            val start = date.atStartOfDay(dayZone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(dayZone).toInstant().toEpochMilli()
            observeOccurrences(start, end, dayZone).map { occurrences -> dayZone to occurrences }
        }

    val uiState: StateFlow<DayUiState> = combine(
        displayedDate,
        occurrences,
        calendarRepository.observeAll(),
    ) { date, (dayZone, occ), calendars ->
        // Built in the zone the list was read in, never in a newer one the list does not cover.
        val items = occ.toTimelineItems(calendars.associate { it.id to it.color.argb }, dayZone)
        DayUiState(TimelineBuilder.build(items, date, dayZone, LocalDate.now(dayZone), System.currentTimeMillis()))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = DayUiState(
            TimelineBuilder.build(emptyList(), LocalDate.now(zone), zone, LocalDate.now(zone), System.currentTimeMillis()),
        ),
    )

    fun onPreviousDay() { displayedDate.value = displayedDate.value.minusDays(1) }
    fun onNextDay() { displayedDate.value = displayedDate.value.plusDays(1) }
    fun onToday() { displayedDate.value = LocalDate.now(zone) }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
