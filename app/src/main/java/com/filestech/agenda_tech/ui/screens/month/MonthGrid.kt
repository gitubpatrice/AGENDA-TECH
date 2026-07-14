package com.filestech.agenda_tech.ui.screens.month

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * Pure calendar-grid maths for the month view — no Android, fully unit-testable.
 *
 * A month is always laid out as [WEEKS] × [DAYS_PER_WEEK] = 42 cells starting on the locale's
 * [DayOfWeek] first-day-of-week, so the grid height is stable across months (leading/trailing days
 * belong to the adjacent months). Six rows always cover any month, including a 31-day month whose
 * 1st falls on the last column.
 */
object MonthGrid {
    const val WEEKS = 6
    const val DAYS_PER_WEEK = 7

    /** The date shown in the top-left cell: the [firstDayOfWeek] on or before the 1st of [yearMonth]. */
    fun firstCell(yearMonth: YearMonth, firstDayOfWeek: DayOfWeek): LocalDate {
        val firstOfMonth = yearMonth.atDay(1)
        val shift = Math.floorMod(firstOfMonth.dayOfWeek.value - firstDayOfWeek.value, DAYS_PER_WEEK)
        return firstOfMonth.minusDays(shift.toLong())
    }

    /** The 6×7 grid of dates, row-major, starting at [firstCell]. */
    fun weeks(yearMonth: YearMonth, firstDayOfWeek: DayOfWeek): List<List<LocalDate>> {
        val start = firstCell(yearMonth, firstDayOfWeek)
        return (0 until WEEKS).map { week ->
            (0 until DAYS_PER_WEEK).map { day ->
                start.plusDays((week * DAYS_PER_WEEK + day).toLong())
            }
        }
    }

    /** Half-open date range `[first cell, last cell + 1 day)` covering the whole visible grid. */
    fun gridRange(yearMonth: YearMonth, firstDayOfWeek: DayOfWeek): Pair<LocalDate, LocalDate> {
        val start = firstCell(yearMonth, firstDayOfWeek)
        return start to start.plusDays((WEEKS * DAYS_PER_WEEK).toLong())
    }

    /** The seven weekday headers in display order, starting at [firstDayOfWeek]. */
    fun weekdayHeaders(firstDayOfWeek: DayOfWeek): List<DayOfWeek> =
        (0 until DAYS_PER_WEEK).map { firstDayOfWeek.plus(it.toLong()) }
}
