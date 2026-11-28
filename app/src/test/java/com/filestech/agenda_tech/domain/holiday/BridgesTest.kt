package com.filestech.agenda_tech.domain.holiday

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The bridges of known years, worked out by hand from their calendars, and the rules every bridge of every
 * country must keep from 1990 to 2060.
 */
class BridgesTest {

    @Test
    fun `France 2027 - Ascension, the 14th of July on a Wednesday, the 11th of November`() {
        val bridges = Bridges.inYear(HolidayCountry.FR, 2027)

        assertThat(bridges.map { it.daysToTake to (it.restStart to it.restEnd) }).containsExactly(
            // Ascension, Thursday 6 May: the 8th of May falls on the Saturday, so it adds nothing.
            listOf(day(2027, 5, 7)) to (day(2027, 5, 6) to day(2027, 5, 9)),
            // The 14th of July, a Wednesday: two days on either side, five days of rest each.
            listOf(day(2027, 7, 12), day(2027, 7, 13)) to (day(2027, 7, 10) to day(2027, 7, 14)),
            listOf(day(2027, 7, 15), day(2027, 7, 16)) to (day(2027, 7, 14) to day(2027, 7, 18)),
            // The 11th of November, a Thursday.
            listOf(day(2027, 11, 12)) to (day(2027, 11, 11) to day(2027, 11, 14)),
        ).inOrder()
        assertThat(bridges.first().holidays.map { it.name }).containsExactly(HolidayName.ASCENSION)
        assertThat(bridges.map { it.restDays }).containsExactly(4, 5, 5, 4).inOrder()
    }

    @Test
    fun `Brazil 2027 - Tiradentes on a Wednesday, three holidays on a Tuesday`() {
        val bridges = Bridges.inYear(HolidayCountry.BR, 2027)

        assertThat(bridges.map { it.daysToTake }).containsExactly(
            listOf(day(2027, 4, 19), day(2027, 4, 20)),
            listOf(day(2027, 4, 22), day(2027, 4, 23)),
            listOf(day(2027, 9, 6)),
            listOf(day(2027, 10, 11)),
            listOf(day(2027, 11, 1)),
        ).inOrder()
        assertThat(bridges.map { it.holidays.single().name }).containsExactly(
            HolidayName.TIRADENTES,
            HolidayName.TIRADENTES,
            HolidayName.INDEPENDENCE_DAY,
            HolidayName.OUR_LADY_OF_APARECIDA,
            HolidayName.ALL_SOULS,
        ).inOrder()
    }

    @Test
    fun `a bridge belongs to the year of its first day to take, the rest running into the next`() {
        // Christmas 2025 and New Year's Day 2026 both fell on a Thursday.
        val christmas = Bridges.inYear(HolidayCountry.FR, 2025).last()
        val newYear = Bridges.inYear(HolidayCountry.FR, 2026).first()

        assertThat(christmas.daysToTake).containsExactly(day(2025, 12, 26))
        assertThat(christmas.restStart to christmas.restEnd).isEqualTo(day(2025, 12, 25) to day(2025, 12, 28))
        assertThat(newYear.daysToTake).containsExactly(day(2026, 1, 2))
        assertThat(newYear.restStart to newYear.restEnd).isEqualTo(day(2026, 1, 1) to day(2026, 1, 4))
        assertThat(Bridges.inYear(HolidayCountry.FR, 2025).map { it.daysToTake }).doesNotContain(listOf(day(2026, 1, 2)))
    }

    @Test
    fun `no holidays, no bridges - before the first year a country's rules are checked for`() {
        assertThat(Bridges.inYear(HolidayCountry.ES, 2006)).isEmpty()
    }

    @ParameterizedTest
    @EnumSource(HolidayCountry::class)
    fun `every bridge is one or two working days that join days off into one stretch`(country: HolidayCountry) {
        for (year in country.firstYear..LAST_YEAR) {
            val holidays = Holidays.between(country, LocalDate.of(year - 1, 1, 1), LocalDate.of(year + 2, 1, 1))
                .mapTo(HashSet()) { it.date }
            val isOff = { d: LocalDate -> d.dayOfWeek in WEEKEND || d in holidays }
            val bridges = Bridges.inYear(country, year)

            assertWithMessage("$country $year in order, none twice")
                .that(bridges.map { it.daysToTake.first() }).isInStrictOrder()
            for (bridge in bridges) {
                val days = bridge.daysToTake
                val where = "$country $year $days"
                assertWithMessage(where).that(days.size).isIn(1..Bridges.MAX_DAYS_TO_TAKE)
                assertWithMessage(where).that(days.first().year).isEqualTo(year)
                assertWithMessage(where).that(days.zipWithNext { a, b -> a.plusDays(1) == b }).doesNotContain(false)
                assertWithMessage(where).that(days.map(isOff)).doesNotContain(true)
                // Off before and after the days to take, all the way to the ends of the stretch, worked past them.
                val stretch = generateSequence(bridge.restStart) { it.plusDays(1) }.takeWhile { !it.isAfter(bridge.restEnd) }
                assertWithMessage(where).that(stretch.filter { it !in days }.map(isOff).toList()).doesNotContain(false)
                assertWithMessage(where).that(isOff(bridge.restStart.minusDays(1))).isFalse()
                assertWithMessage(where).that(isOff(bridge.restEnd.plusDays(1))).isFalse()
                assertWithMessage(where).that(bridge.restDays).isAtLeast(days.size + 2)
                // Made of at least one holiday on a weekday, inside the stretch.
                assertWithMessage(where).that(bridge.holidays).isNotEmpty()
                for (holiday in bridge.holidays) {
                    assertWithMessage(where).that(holiday.date.dayOfWeek).isNotIn(WEEKEND)
                    assertWithMessage(where).that(holiday.date).isAtLeast(bridge.restStart)
                    assertWithMessage(where).that(holiday.date).isAtMost(bridge.restEnd)
                }
            }
        }
    }

    private companion object {
        const val LAST_YEAR = 2060
        val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

        fun day(year: Int, month: Int, dayOfMonth: Int): LocalDate = LocalDate.of(year, month, dayOfMonth)
    }
}
