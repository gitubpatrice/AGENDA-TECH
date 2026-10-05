package com.filestech.agenda_tech.ui.screens.month

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

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
)

/**
 * Immutable state for the month screen. Locale-agnostic on purpose: [yearMonth]/[firstDayOfWeek]/
 * dates are raw `java.time` values the Composable formats with the viewer's locale.
 */
data class MonthUiState(
    val yearMonth: YearMonth,
    val firstDayOfWeek: DayOfWeek,
    /**
     * The grid of the shown month and of its two neighbours, keyed by month, so a swipe slides in a
     * page that is already filled in.
     */
    val pages: Map<YearMonth, List<List<DayCellData>>>,
    val selectedDate: LocalDate,
    val selectedDayOccurrences: List<OccurrenceData>,
    val showWeekNumbers: Boolean,
    /** ISO week number for each of the 6 grid rows (parallel to [weeks]); shown when [showWeekNumbers]. */
    val weekNumbers: List<Int>,
    val isLoading: Boolean,
) {
    /** The shown month's 6×7 grid. */
    val weeks: List<List<DayCellData>> get() = pages.getValue(yearMonth)
}
