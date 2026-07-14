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
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject

data class WeekUiState(
    val weekStart: LocalDate,
    val days: List<DayTimelineData>,
)

@HiltViewModel
class WeekViewModel @Inject constructor(
    private val observeOccurrences: ObserveOccurrencesInRangeUseCase,
    calendarRepository: CalendarRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    private val weekStart = MutableStateFlow(startOfWeek(LocalDate.now(zone)))

    @OptIn(ExperimentalCoroutinesApi::class)
    private val occurrences = weekStart.flatMapLatest { start ->
        val startMillis = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = start.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
        observeOccurrences(startMillis, endMillis)
    }

    val uiState: StateFlow<WeekUiState> = combine(
        weekStart,
        occurrences,
        calendarRepository.observeAll(),
    ) { start, occ, calendars ->
        val items = occ.toTimelineItems(calendars.associate { it.id to it.color.argb })
        buildWeek(start, items)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = buildWeek(startOfWeek(LocalDate.now(zone)), emptyList()),
    )

    val weekStartValue: LocalDate get() = weekStart.value

    fun onPreviousWeek() { weekStart.value = weekStart.value.minusWeeks(1) }
    fun onNextWeek() { weekStart.value = weekStart.value.plusWeeks(1) }
    fun onToday() { weekStart.value = startOfWeek(LocalDate.now(zone)) }

    private fun buildWeek(start: LocalDate, items: List<TimelineItem>): WeekUiState {
        val today = LocalDate.now(zone)
        val now = System.currentTimeMillis()
        val days = (0L until DAYS_PER_WEEK).map { offset ->
            val date = start.plusDays(offset)
            TimelineBuilder.build(items.filter { overlapsDay(it, date) }, date, zone, today, now)
        }
        return WeekUiState(weekStart = start, days = days)
    }

    private fun overlapsDay(item: TimelineItem, date: LocalDate): Boolean {
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return item.startUtcMillis < dayEnd && item.endUtcMillis > dayStart
    }

    private fun startOfWeek(date: LocalDate): LocalDate =
        date.minusDays(((date.dayOfWeek.value - firstDayOfWeek.value + DAYS_PER_WEEK) % DAYS_PER_WEEK).toLong())

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val DAYS_PER_WEEK = 7
    }
}
