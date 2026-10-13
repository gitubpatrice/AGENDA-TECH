package com.filestech.agenda_tech.ui.screens.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.core.time.DeviceZone
import com.filestech.agenda_tech.domain.holiday.Holiday
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.usecase.ObserveHolidaysUseCase
import com.filestech.agenda_tech.domain.usecase.ObserveOccurrencesInRangeUseCase
import com.filestech.agenda_tech.ui.screens.timeline.TimelineItem
import com.filestech.agenda_tech.ui.screens.timeline.TimelineWindow
import com.filestech.agenda_tech.ui.screens.timeline.toTimelineItems
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** A day, its public holidays and its events, for the agenda list. */
data class AgendaDay(
    val date: LocalDate,
    val items: List<TimelineItem>,
    val holidays: List<Holiday> = emptyList(),
)

data class AgendaUiState(
    val days: List<AgendaDay>,
    val isLoading: Boolean,
    /** The zone the days were counted in, which the screen formats the clock times in. */
    val zone: ZoneId,
)

/**
 * Drives the agenda (list) view: streams the occurrences of a wide window around today (roughly a
 * year back to a year ahead, so imported past appointments are not hidden) and groups them by day.
 * Only days that actually have events appear; the screen scrolls to today on open.
 */
@HiltViewModel
class AgendaViewModel @Inject constructor(
    observeOccurrences: ObserveOccurrencesInRangeUseCase,
    observeHolidays: ObserveHolidaysUseCase,
    calendarRepository: CalendarRepository,
    private val deviceZone: DeviceZone,
) : ViewModel() {

    /** The phone's zone now — see [DeviceZone]: read once, the list kept its first zone after a journey. */
    private val zone: ZoneId get() = deviceZone.zone.value

    /**
     * Aujourd'hui, relu a CHAQUE acces (audit, gravite faible).
     *
     * C'etait un `val` fige a la construction. Les trois autres vues de l'application
     * (MonthViewModel, DayViewModel, WeekViewModel) ont toutes un `onToday()` qui relit
     * `LocalDate.now(zone)` ; celle-ci n'en avait pas, et l'ecran s'ancrait donc sur la VEILLE pour
     * une application laissee ouverte par-dessus minuit.
     *
     * La fenetre de donnees, elle, reste calculee une fois : elle couvre un an de part et d'autre,
     * donc un jour d'ecart n'y cache rien. Ce qui se voyait, c'est le positionnement initial.
     */
    val startDate: LocalDate get() = LocalDate.now(zone)

    // The window is read again when the zone changes, and each list is grouped in the zone it was read in.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val window = deviceZone.zone.flatMapLatest { windowZone ->
        val today = LocalDate.now(windowZone)
        val from = today.minusDays(PAST_DAYS)
        val until = today.plusDays(FUTURE_DAYS)
        combine(
            observeOccurrences(
                from.atStartOfDay(windowZone).toInstant().toEpochMilli(),
                until.atStartOfDay(windowZone).toInstant().toEpochMilli(),
                windowZone,
            ),
            observeHolidays(from, until),
        ) { occurrences, holidays -> TimelineWindow(windowZone, occurrences, holidays) }
    }

    val uiState: StateFlow<AgendaUiState> = combine(
        window,
        calendarRepository.observeAll(),
    ) { window, calendars ->
        val listZone = window.zone
        val items = window.occurrences.toTimelineItems(calendars.associate { it.id to it.color.argb }, listZone)
        val itemsByDate = items.groupBy { Instant.ofEpochMilli(it.startUtcMillis).atZone(listZone).toLocalDate() }
        // A day with a holiday and no event is listed too: the holiday is worth knowing on its own.
        val days = (itemsByDate.keys + window.holidays.keys).sorted().map { date ->
            AgendaDay(
                date = date,
                items = itemsByDate[date].orEmpty().sortedWith(compareBy({ !it.allDay }, { it.startUtcMillis })),
                holidays = window.holidays[date].orEmpty(),
            )
        }
        AgendaUiState(days = days, isLoading = false, zone = listZone)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = AgendaUiState(days = emptyList(), isLoading = true, zone = zone),
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val PAST_DAYS = 366L
        const val FUTURE_DAYS = 366L
    }
}
