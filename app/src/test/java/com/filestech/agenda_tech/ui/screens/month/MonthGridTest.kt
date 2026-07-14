package com.filestech.agenda_tech.ui.screens.month

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class MonthGridTest {

    @Test
    fun `grid is always six weeks of seven days`() {
        val weeks = MonthGrid.weeks(YearMonth.of(2025, 6), DayOfWeek.MONDAY)
        assertThat(weeks).hasSize(MonthGrid.WEEKS)
        weeks.forEach { assertThat(it).hasSize(MonthGrid.DAYS_PER_WEEK) }
    }

    @Test
    fun `first cell backfills to the first-day-of-week on or before the 1st (Monday-start)`() {
        // 2025-06-01 is a Sunday; the Monday-start grid begins on Monday 2025-05-26.
        assertThat(MonthGrid.firstCell(YearMonth.of(2025, 6), DayOfWeek.MONDAY))
            .isEqualTo(LocalDate.of(2025, 5, 26))
    }

    @Test
    fun `first cell is the 1st itself when it already is the first-day-of-week (Sunday-start)`() {
        // 2025-06-01 is a Sunday, so a Sunday-start grid begins exactly there.
        assertThat(MonthGrid.firstCell(YearMonth.of(2025, 6), DayOfWeek.SUNDAY))
            .isEqualTo(LocalDate.of(2025, 6, 1))
    }

    @Test
    fun `grid range is a 42-day half-open span from the first cell`() {
        val (start, end) = MonthGrid.gridRange(YearMonth.of(2025, 6), DayOfWeek.MONDAY)
        assertThat(start).isEqualTo(LocalDate.of(2025, 5, 26))
        assertThat(end).isEqualTo(start.plusDays(42))
    }

    @Test
    fun `weekday headers start at the requested first day of week`() {
        assertThat(MonthGrid.weekdayHeaders(DayOfWeek.MONDAY))
            .containsExactly(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
            ).inOrder()
    }

    @Test
    fun `the grid contains every day of the target month`() {
        val yearMonth = YearMonth.of(2025, 2) // 28 days, a good edge case
        val allDates = MonthGrid.weeks(yearMonth, DayOfWeek.MONDAY).flatten()
        (1..yearMonth.lengthOfMonth()).forEach { day ->
            assertThat(allDates).contains(yearMonth.atDay(day))
        }
    }
}
