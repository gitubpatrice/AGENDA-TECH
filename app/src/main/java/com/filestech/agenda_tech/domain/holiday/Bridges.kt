package com.filestech.agenda_tech.domain.holiday

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * A bridge: one or two working days caught between public holidays and a weekend, which, taken off, join
 * them into one stretch of rest — the Friday after an Ascension Thursday gives four days in a row.
 *
 * [daysToTake] are those working days, in order. [restStart] to [restEnd], both included, is the whole
 * stretch they give, themselves among it. [holidays] are the holidays on a weekday in that stretch: what
 * the bridge is made of — a holiday on a Saturday adds no day off, so it is not one of them.
 */
data class Bridge(
    val daysToTake: List<LocalDate>,
    val restStart: LocalDate,
    val restEnd: LocalDate,
    val holidays: List<Holiday>,
) {
    /** How many days in a row are off, the days taken included. */
    val restDays: Int get() = ChronoUnit.DAYS.between(restStart, restEnd).toInt() + 1
}

/**
 * The bridges of a year, worked out from [Holidays] alone: Saturday and Sunday off, every other day worked
 * unless it is a national holiday. That is all the app knows — neither a region's holidays nor anyone's own
 * working week — so a bridge here is a suggestion, as the holidays are. Pure, hence unit-testable.
 */
object Bridges {

    /** Past two working days to take, a stretch is a week's holiday rather than a bridge. */
    const val MAX_DAYS_TO_TAKE = 2

    /**
     * The bridges of [country] whose first day to take falls in [year], by date. One beginning on the 31st
     * of December belongs to that year, even when the rest it gives ends in January.
     */
    fun inYear(country: HolidayCountry, year: Int): List<Bridge> {
        val first = LocalDate.of(year, 1, 1)
        val next = first.plusYears(1)
        // A margin either side: the rest a bridge gives can run from one year into the next.
        val holidays = Holidays.between(country, first.minusDays(MARGIN_DAYS), next.plusDays(MARGIN_DAYS))
        val holidayDates = holidays.mapTo(HashSet()) { it.date }
        val isOff = { day: LocalDate -> day.isWeekend() || day in holidayDates }

        val bridges = mutableListOf<Bridge>()
        var day = first
        while (day.isBefore(next)) {
            // Only the first working day after a day off starts a run: a run begun the day before is
            // already counted, or too long to be a bridge.
            if (isOff(day) || !isOff(day.minusDays(1))) {
                day = day.plusDays(1)
                continue
            }
            // One day more than a bridge can have, to tell a short run from a long one.
            val run = generateSequence(day) { it.plusDays(1) }
                .takeWhile { !isOff(it) }
                .take(MAX_DAYS_TO_TAKE + 1)
                .toList()
            if (run.size <= MAX_DAYS_TO_TAKE) {
                // The days off on either side, as far as they go: a weekend, a holiday, both.
                val restStart = generateSequence(run.first().minusDays(1)) { it.minusDays(1) }.takeWhile(isOff).last()
                val restEnd = generateSequence(run.last().plusDays(1)) { it.plusDays(1) }.takeWhile(isOff).last()
                val madeOf = holidays.filter {
                    !it.date.isBefore(restStart) && !it.date.isAfter(restEnd) && !it.date.isWeekend()
                }
                bridges += Bridge(run, restStart, restEnd, madeOf)
            }
            day = run.last().plusDays(1)
        }
        return bridges
    }

    private fun LocalDate.isWeekend(): Boolean = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY

    /** Wider than any stretch of days off, so the holidays at its far end are always known. */
    private const val MARGIN_DAYS = 31L
}
