package com.filestech.agenda_tech.ui.screens.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.core.time.DeviceZone
import com.filestech.agenda_tech.domain.holiday.Holiday
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.repository.SettingsRepository
import com.filestech.agenda_tech.domain.settings.toDayOfWeek
import com.filestech.agenda_tech.domain.usecase.ObserveHolidaysUseCase
import com.filestech.agenda_tech.domain.usecase.ObserveOccurrencesInRangeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
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
    private val observeHolidays: ObserveHolidaysUseCase,
    private val calendarRepository: CalendarRepository,
    private val settingsRepository: SettingsRepository,
    private val deviceZone: DeviceZone,
) : ViewModel() {

    /**
     * The phone's zone now; the query below follows [DeviceZone.zone]. Taken once at construction, an
     * open screen kept the zone it was opened in after a journey.
     */
    private val zone: ZoneId get() = deviceZone.zone.value

    /**
     * The language the screen is drawn in, sent by it ([onLocaleChange]): it decides the first day of
     * the week when the setting says "system", and the view model outlives a change of language.
     */
    private val appLocale = MutableStateFlow(Locale.getDefault())

    /** Any day within the displayed week; the week start is derived from it + the first-day setting. */
    private val referenceDate = MutableStateFlow(LocalDate.now(zone))

    private val firstDayOfWeekFlow = combine(settingsRepository.settings, appLocale) { settings, locale ->
        settings.weekStart.toDayOfWeek(locale)
    }.distinctUntilChanged()

    private val weekStartFlow = combine(referenceDate, firstDayOfWeekFlow) { reference, firstDay ->
        startOfWeek(reference, firstDay)
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val window = combine(weekStartFlow, deviceZone.zone) { start, now -> start to now }
        .flatMapLatest { (start, weekZone) ->
            val startMillis = start.atStartOfDay(weekZone).toInstant().toEpochMilli()
            val endMillis = start.plusWeeks(1).atStartOfDay(weekZone).toInstant().toEpochMilli()
            combine(
                observeOccurrences(startMillis, endMillis, weekZone),
                observeHolidays(start, start.plusWeeks(1)),
            ) { occurrences, holidays -> TimelineWindow(weekZone, occurrences, holidays) }
        }

    val uiState: StateFlow<WeekUiState> = combine(
        weekStartFlow,
        window,
        calendarRepository.observeAll(),
    ) { start, window, calendars ->
        // Built in the zone the list was read in, never in a newer one the list does not cover.
        val items = window.occurrences.toTimelineItems(calendars.associate { it.id to it.color.argb }, window.zone)
        buildWeek(start, items, window.holidays, window.zone)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = buildWeek(
            startOfWeek(LocalDate.now(zone), WeekFields.of(appLocale.value).firstDayOfWeek),
            emptyList(),
            emptyMap(),
            zone,
        ),
    )

    fun onPreviousWeek() { referenceDate.value = referenceDate.value.minusWeeks(1) }
    fun onNextWeek() { referenceDate.value = referenceDate.value.plusWeeks(1) }
    fun onToday() { referenceDate.value = LocalDate.now(zone) }

    /** The language the screen is drawn in — see [appLocale]. */
    fun onLocaleChange(locale: Locale) {
        appLocale.value = locale
    }

    private fun buildWeek(
        start: LocalDate,
        items: List<TimelineItem>,
        holidays: Map<LocalDate, List<Holiday>>,
        zone: ZoneId,
    ): WeekUiState {
        val today = LocalDate.now(zone)
        val now = System.currentTimeMillis()
        val days = (0L until DAYS_PER_WEEK).map { offset ->
            val date = start.plusDays(offset)
            TimelineBuilder.build(items.filter { overlapsDay(it, date, zone) }, date, zone, today, now, holidays[date].orEmpty())
        }
        return WeekUiState(weekStart = start, days = days)
    }

    private fun overlapsDay(item: TimelineItem, date: LocalDate, zone: ZoneId): Boolean {
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return item.startUtcMillis < dayEnd && item.endUtcMillis > dayStart
    }

    private fun startOfWeek(date: LocalDate, firstDay: DayOfWeek): LocalDate =
        date.minusDays(((date.dayOfWeek.value - firstDay.value + DAYS_PER_WEEK) % DAYS_PER_WEEK).toLong())

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val DAYS_PER_WEEK = 7
    }
}
