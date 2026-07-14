package com.filestech.agenda_tech.ui.screens.month

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.domain.recurrence.EventOccurrence
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
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject

/**
 * Drives the month view: tracks the displayed [YearMonth] and the selected day, streams the
 * occurrences of the whole visible grid (via [ObserveOccurrencesInRangeUseCase]) and folds them —
 * together with calendar colours — into a [MonthUiState].
 *
 * The display zone is the device zone and the first-day-of-week follows the device locale; both are
 * captured once. Occurrences are keyed to a day by their start date for the grid dots, while the
 * selected-day list uses true overlap so multi-day events still show on each day they cover.
 */
@HiltViewModel
class MonthViewModel @Inject constructor(
    private val observeOccurrences: ObserveOccurrencesInRangeUseCase,
    calendarRepository: CalendarRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val firstDayOfWeek: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek

    private val displayedMonth = MutableStateFlow(YearMonth.now(zone))
    private val selectedDate = MutableStateFlow(LocalDate.now(zone))

    @OptIn(ExperimentalCoroutinesApi::class)
    private val windowOccurrences = displayedMonth.flatMapLatest { month ->
        val (startDate, endDate) = MonthGrid.gridRange(month, firstDayOfWeek)
        observeOccurrences(startDate.toStartOfDayUtc(), endDate.toStartOfDayUtc())
    }

    val uiState: StateFlow<MonthUiState> = combine(
        displayedMonth,
        selectedDate,
        windowOccurrences,
        calendarRepository.observeAll(),
    ) { month, selected, occurrences, calendars ->
        buildState(month, selected, occurrences, calendars.associate { it.id to it.color.argb }, isLoading = false)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = buildState(
            YearMonth.now(zone),
            LocalDate.now(zone),
            emptyList(),
            emptyMap(),
            isLoading = true,
        ),
    )

    fun onPreviousMonth() {
        displayedMonth.value = displayedMonth.value.minusMonths(1)
    }

    fun onNextMonth() {
        displayedMonth.value = displayedMonth.value.plusMonths(1)
    }

    fun onToday() {
        displayedMonth.value = YearMonth.now(zone)
        selectedDate.value = LocalDate.now(zone)
    }

    /** Selecting a leading/trailing cell that belongs to an adjacent month navigates to it. */
    fun onSelectDate(date: LocalDate) {
        selectedDate.value = date
        val month = YearMonth.from(date)
        if (month != displayedMonth.value) displayedMonth.value = month
    }

    private fun buildState(
        month: YearMonth,
        selected: LocalDate,
        occurrences: List<EventOccurrence>,
        colorByCalendarId: Map<Long, Int>,
        isLoading: Boolean,
    ): MonthUiState {
        val today = LocalDate.now(zone)
        val byStartDate = occurrences.groupBy { it.startUtcMillis.toLocalDate() }

        val weeks = MonthGrid.weeks(month, firstDayOfWeek).map { week ->
            week.map { date ->
                val dayOccurrences = byStartDate[date].orEmpty()
                DayCellData(
                    date = date,
                    isInMonth = YearMonth.from(date) == month,
                    isToday = date == today,
                    isSelected = date == selected,
                    eventColors = dayOccurrences.take(MAX_DOTS).map { colorOf(it, colorByCalendarId) },
                    eventCount = dayOccurrences.size,
                )
            }
        }

        val selectedStart = selected.toStartOfDayUtc()
        val selectedEnd = selected.plusDays(1).toStartOfDayUtc()
        val selectedDayOccurrences = occurrences
            .filter { it.startUtcMillis < selectedEnd && it.endUtcMillis > selectedStart }
            .sortedWith(compareBy({ !it.event.allDay }, { it.startUtcMillis }))
            .map {
                OccurrenceData(
                    eventId = it.event.id,
                    title = it.event.title,
                    startUtcMillis = it.startUtcMillis,
                    endUtcMillis = it.endUtcMillis,
                    allDay = it.event.allDay,
                    colorArgb = colorOf(it, colorByCalendarId),
                )
            }

        return MonthUiState(
            yearMonth = month,
            firstDayOfWeek = firstDayOfWeek,
            weeks = weeks,
            selectedDate = selected,
            selectedDayOccurrences = selectedDayOccurrences,
            isLoading = isLoading,
        )
    }

    private fun colorOf(occurrence: EventOccurrence, colorByCalendarId: Map<Long, Int>): Int =
        occurrence.event.colorOverride?.argb
            ?: colorByCalendarId[occurrence.event.calendarId]
            ?: CalendarColor.DEFAULT.argb

    private fun Long.toLocalDate(): LocalDate =
        Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

    private fun LocalDate.toStartOfDayUtc(): Long =
        atStartOfDay(zone).toInstant().toEpochMilli()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val MAX_DOTS = 4
    }
}
