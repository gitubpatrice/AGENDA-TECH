package com.filestech.agenda_tech.ui.screens.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.usecase.ObserveOccurrencesInRangeUseCase
import com.filestech.agenda_tech.ui.screens.timeline.TimelineItem
import com.filestech.agenda_tech.ui.screens.timeline.toTimelineItems
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** A day and its events, for the agenda list. */
data class AgendaDay(
    val date: LocalDate,
    val items: List<TimelineItem>,
)

data class AgendaUiState(
    val days: List<AgendaDay>,
    val isLoading: Boolean,
)

/**
 * Drives the agenda (list) view: streams the occurrences of a rolling window starting today and
 * groups them by day. Only days that actually have events appear.
 */
@HiltViewModel
class AgendaViewModel @Inject constructor(
    observeOccurrences: ObserveOccurrencesInRangeUseCase,
    calendarRepository: CalendarRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    val startDate: LocalDate = LocalDate.now(zone)

    private val windowStart = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
    private val windowEnd = startDate.plusDays(WINDOW_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()

    val uiState: StateFlow<AgendaUiState> = combine(
        observeOccurrences(windowStart, windowEnd),
        calendarRepository.observeAll(),
    ) { occurrences, calendars ->
        val items = occurrences.toTimelineItems(calendars.associate { it.id to it.color.argb })
        val days = items
            .groupBy { Instant.ofEpochMilli(it.startUtcMillis).atZone(zone).toLocalDate() }
            .toSortedMap()
            .map { (date, dayItems) ->
                AgendaDay(
                    date = date,
                    items = dayItems.sortedWith(compareBy({ !it.allDay }, { it.startUtcMillis })),
                )
            }
        AgendaUiState(days = days, isLoading = false)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = AgendaUiState(days = emptyList(), isLoading = true),
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val WINDOW_DAYS = 90L
    }
}
