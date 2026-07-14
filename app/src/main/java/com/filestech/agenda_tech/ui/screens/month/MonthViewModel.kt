package com.filestech.agenda_tech.ui.screens.month

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.domain.recurrence.EventOccurrence
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.repository.SettingsRepository
import com.filestech.agenda_tech.domain.settings.toDayOfWeek
import com.filestech.agenda_tech.domain.usecase.ObserveOccurrencesInRangeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
 * Drives the month view: tracks the displayed [YearMonth] and selected day, streams the occurrences
 * of the whole visible grid ([ObserveOccurrencesInRangeUseCase]) and folds them — with calendar
 * colours and the user's first-day-of-week / week-number settings — into a [MonthUiState].
 */
@HiltViewModel
class MonthViewModel @Inject constructor(
    private val observeOccurrences: ObserveOccurrencesInRangeUseCase,
    private val calendarRepository: CalendarRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val locale: Locale = Locale.getDefault()

    private val displayedMonth = MutableStateFlow(YearMonth.now(zone))
    private val selectedDate = MutableStateFlow(LocalDate.now(zone))

    private val firstDayOfWeekFlow = settingsRepository.settings
        .map { it.weekStart.toDayOfWeek(locale) }
        .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val windowOccurrences = combine(displayedMonth, firstDayOfWeekFlow) { month, firstDay ->
        month to firstDay
    }.flatMapLatest { (month, firstDay) ->
        val (startDate, endDate) = MonthGrid.gridRange(month, firstDay)
        observeOccurrences(startDate.toStartOfDayUtc(), endDate.toStartOfDayUtc())
    }

    val uiState: StateFlow<MonthUiState> = combine(
        displayedMonth,
        selectedDate,
        windowOccurrences,
        calendarRepository.observeAll(),
        settingsRepository.settings,
    ) { month, selected, occurrences, calendars, settings ->
        buildState(
            month = month,
            selected = selected,
            occurrences = occurrences,
            colorByCalendarId = calendars.associate { it.id to it.color.argb },
            firstDayOfWeek = settings.weekStart.toDayOfWeek(locale),
            showWeekNumbers = settings.showWeekNumbers,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = buildState(
            month = YearMonth.now(zone),
            selected = LocalDate.now(zone),
            occurrences = emptyList(),
            colorByCalendarId = emptyMap(),
            firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek,
            showWeekNumbers = false,
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
        firstDayOfWeek: DayOfWeek,
        showWeekNumbers: Boolean,
        isLoading: Boolean,
    ): MonthUiState {
        val today = LocalDate.now(zone)
        val byStartDate = occurrences.groupBy { it.startUtcMillis.toLocalDate() }

        val weekRows = MonthGrid.weeks(month, firstDayOfWeek)
        val weeks = weekRows.map { week ->
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
        // ISO week number, read from the mid-week cell so it's stable whatever the first day is.
        val weekNumbers = weekRows.map { it[MID_WEEK_INDEX].get(WeekFields.ISO.weekOfWeekBasedYear()) }

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
            showWeekNumbers = showWeekNumbers,
            weekNumbers = weekNumbers,
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
        const val MID_WEEK_INDEX = 3
    }
}
