package com.filestech.agenda_tech.ui.screens.month

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * One cell of the month grid. [eventColors] are the (capped) resolved ARGB colours of the day's
 * events for the indicator dots; [eventCount] is the true total (may exceed the dots shown).
 */
data class DayCellData(
    val date: LocalDate,
    val isInMonth: Boolean,
    val isToday: Boolean,
    val isSelected: Boolean,
    val eventColors: List<Int>,
    val eventCount: Int,
)

/**
 * A single occurrence rendered in the selected-day list. Times are absolute instants; the UI
 * formats them in the display zone/locale. [eventId] routes a tap to the (future) event editor.
 */
data class OccurrenceData(
    val eventId: Long,
    val title: String,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
    val allDay: Boolean,
    val colorArgb: Int,
)

/**
 * Immutable state for the month screen. Locale-agnostic on purpose: [yearMonth]/[firstDayOfWeek]/
 * dates are raw `java.time` values the Composable formats with the viewer's locale.
 */
data class MonthUiState(
    val yearMonth: YearMonth,
    val firstDayOfWeek: DayOfWeek,
    val weeks: List<List<DayCellData>>,
    val selectedDate: LocalDate,
    val selectedDayOccurrences: List<OccurrenceData>,
    val isLoading: Boolean,
)
