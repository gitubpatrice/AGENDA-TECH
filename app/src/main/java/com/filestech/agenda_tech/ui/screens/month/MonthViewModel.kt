package com.filestech.agenda_tech.ui.screens.month

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.domain.recurrence.EventOccurrence
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.repository.EventRepository
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
import kotlinx.coroutines.launch
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
    private val eventRepository: EventRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val locale: Locale = Locale.getDefault()

    private val displayedMonth = MutableStateFlow(YearMonth.now(zone))
    private val selectedDate = MutableStateFlow(LocalDate.now(zone))

    private val firstDayOfWeekFlow = settingsRepository.settings
        .map { it.weekStart.toDayOfWeek(locale) }
        .distinctUntilChanged()

    // Audit DATA-4 — only the settings this view actually uses, so unrelated toggles don't rebuild the grid.
    private val monthSettingsFlow = settingsRepository.settings
        .map { it.weekStart to it.showWeekNumbers }
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
        monthSettingsFlow,
    ) { month, selected, occurrences, calendars, settingsPair ->
        buildState(
            month = month,
            selected = selected,
            occurrences = occurrences,
            colorByCalendarId = calendars.associate { it.id to it.color.argb },
            firstDayOfWeek = settingsPair.first.toDayOfWeek(locale),
            showWeekNumbers = settingsPair.second,
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

    /**
     * Whether to offer restoring a backup.
     *
     * Shown only on an agenda that holds nothing at all — which is exactly the state of a fresh
     * install after a new phone, the one moment the backup exists for. Without this, the user has to
     * already know the feature exists and go looking for it in Settings → Privacy.
     *
     * Kept out of [MonthUiState]: that flow already combines five sources (the typed limit), and this
     * one has nothing to do with drawing the grid.
     */
    val showRestorePrompt: StateFlow<Boolean> = combine(
        eventRepository.observeIsEmpty(),
        settingsRepository.settings.map { it.restorePromptDismissed }.distinctUntilChanged(),
    ) { isEmpty, dismissed -> isEmpty && !dismissed }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = false,
        )

    /**
     * The user answered the offer — restored, or waved it away. Either way it must not come back:
     * a deliberately empty agenda would otherwise be nagged on every single launch.
     */
    fun dismissRestorePrompt() = viewModelScope.launch {
        settingsRepository.update { it.copy(restorePromptDismissed = true) }
    }

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

    /** Jump straight to a month (used by the swipe pager once it settles on a page). */
    fun showMonth(month: YearMonth) {
        if (displayedMonth.value != month) displayedMonth.value = month
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

        val weekRows = MonthGrid.weeks(month, firstDayOfWeek)
        val weeks = weekRows.map { week ->
            week.map { date ->
                // FIAB-2 — dots must appear on every day an event overlaps, not only its start day,
                // so multi-day / all-day-span events (e.g. a 2-day holiday) show on each covered cell.
                val dayStart = date.toStartOfDayUtc()
                val dayEnd = date.plusDays(1).toStartOfDayUtc()
                val dayOccurrences = occurrences.filter {
                    it.startUtcMillis < dayEnd && it.endUtcMillis > dayStart
                }
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
