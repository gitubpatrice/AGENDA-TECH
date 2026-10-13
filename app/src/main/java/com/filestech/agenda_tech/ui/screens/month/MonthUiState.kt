package com.filestech.agenda_tech.ui.screens.month

import com.filestech.agenda_tech.domain.holiday.Holiday
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * One cell of the month grid. [events] are every occurrence overlapping the day, all-day first then
 * by start — the dots, the titles and the rows each draw what fits of the same list.
 */
data class DayCellData(
    val date: LocalDate,
    val isInMonth: Boolean,
    val isToday: Boolean,
    val isSelected: Boolean,
    val events: List<OccurrenceData>,
    /** The public holidays on this day (see ObserveHolidaysUseCase) — none unless a country is picked. */
    val holidays: List<Holiday> = emptyList(),
)

/**
 * A single occurrence rendered in the selected-day list. Times are absolute instants; the UI
 * formats them in the display zone/locale. [eventId] routes a tap to the event editor.
 *
 * [startUtcMillis] / [endUtcMillis] are the occurrence's place on the phone's calendar, which is what
 * every day and every clock time is drawn from; [occurrenceStartUtcMillis] is the instant the
 * occurrence is known by, which is what a tap hands the editor. They differ only for an all-day
 * occurrence created in another time zone — see `domain/recurrence/AllDayPlacement.kt`.
 */
data class OccurrenceData(
    val eventId: Long,
    val title: String,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
    val occurrenceStartUtcMillis: Long,
    val allDay: Boolean,
    val colorArgb: Int,
    /** Age this birthday occurrence marks, or null. */
    val birthdayAge: Int? = null,
    /**
     * Where it takes place, on one line (see [com.filestech.agenda_tech.domain.location.PlaceLabel]) —
     * or its GPS coordinates when they are its only place, since a tap on them still opens the map —
     * or null.
     */
    val place: String? = null,
    /** The `geo:` link a tap on [place] opens ([com.filestech.agenda_tech.domain.location.GeoLink.forEvent]). */
    val mapUri: String? = null,
    /** The event's notes, or null when there are none. */
    val description: String? = null,
)

/**
 * Immutable state for the month screen. Locale-agnostic on purpose: [yearMonth]/[firstDayOfWeek]/
 * dates are raw `java.time` values the Composable formats with the viewer's locale.
 */
data class MonthUiState(
    /**
     * The zone the state was built in — its days, its "today", and the clock times the screen formats
     * (`MonthScreen` provides it as `LocalDeviceZone`). Carried rather than read again by the screen: a
     * state replayed after a change of zone must be drawn in the zone it was counted in.
     */
    val zone: ZoneId,
    val yearMonth: YearMonth,
    val firstDayOfWeek: DayOfWeek,
    /**
     * The grid of the shown month and of its two neighbours, keyed by month, so a swipe slides in a
     * page that is already filled in.
     */
    val pages: Map<YearMonth, List<List<DayCellData>>>,
    val selectedDate: LocalDate,
    val selectedDayOccurrences: List<OccurrenceData>,
    /** The selected day's public holidays, shown above its events in the day's list. */
    val selectedDayHolidays: List<Holiday> = emptyList(),
    val showWeekNumbers: Boolean,
    /** ISO week number for each of the 6 grid rows (parallel to [weeks]); shown when [showWeekNumbers]. */
    val weekNumbers: List<Int>,
    val isLoading: Boolean,
) {
    /** The shown month's 6×7 grid. */
    val weeks: List<List<DayCellData>> get() = pages.getValue(yearMonth)
}
