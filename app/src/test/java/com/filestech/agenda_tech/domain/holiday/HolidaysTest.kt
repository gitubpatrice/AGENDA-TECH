package com.filestech.agenda_tech.domain.holiday

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.LocalDate

/**
 * The holidays computed here against an independent reference: python-holidays 0.106, every national
 * holiday of the ten countries from 1990 to 2060, generated once into `holidays/reference-1990-2060.txt`.
 * Every date, every name, every substitute day must match — a holiday missing, one too many, one on the
 * wrong day or under the wrong name fails.
 */
class HolidaysTest {

    @ParameterizedTest
    @EnumSource(HolidayCountry::class)
    fun `every holiday of the reference, and nothing else`(country: HolidayCountry) {
        val expected = REFERENCE.filter { it.first == country }.map { it.second }
        assertWithMessage("the reference must cover $country, or this test compares nothing").that(expected).isNotEmpty()

        val computed = Holidays.between(country, LocalDate.of(country.firstYear, 1, 1), LocalDate.of(2061, 1, 1))

        assertThat(computed).containsExactlyElementsIn(expected)
    }

    @Test
    fun `easter sunday on known years`() {
        assertThat(Holidays.easterSunday(2024)).isEqualTo(LocalDate.of(2024, 3, 31))
        assertThat(Holidays.easterSunday(2025)).isEqualTo(LocalDate.of(2025, 4, 20))
        assertThat(Holidays.easterSunday(2026)).isEqualTo(LocalDate.of(2026, 4, 5))
        assertThat(Holidays.easterSunday(2038)).isEqualTo(LocalDate.of(2038, 4, 25))
        assertThat(Holidays.easterSunday(2285)).isEqualTo(LocalDate.of(2285, 3, 22))
    }

    @Test
    fun `nothing is shown before the first year the rules are checked for`() {
        assertThat(Holidays.between(HolidayCountry.ES, LocalDate.of(2007, 1, 1), LocalDate.of(2008, 1, 1))).isEmpty()
        assertThat(Holidays.between(HolidayCountry.DE, LocalDate.of(1990, 1, 1), LocalDate.of(1991, 1, 1))).isEmpty()
    }

    @Test
    fun `a day off standing in for a holiday is found across the turn of the year`() {
        // New Year's Day 2022 fell on a Saturday: the Americans took the Friday before, in 2021.
        val lastWeek = Holidays.between(HolidayCountry.US, LocalDate.of(2021, 12, 27), LocalDate.of(2022, 1, 1))

        assertThat(lastWeek).contains(Holiday(LocalDate.of(2021, 12, 31), HolidayName.NEW_YEAR, observed = true))
    }

    @Test
    fun `an empty or reversed range has no holidays`() {
        val day = LocalDate.of(2026, 12, 25)
        assertThat(Holidays.between(HolidayCountry.FR, day, day)).isEmpty()
        assertThat(Holidays.between(HolidayCountry.FR, day, day.minusDays(1))).isEmpty()
    }

    private companion object {
        val REFERENCE_NAMES = mapOf(
            "New Year's Day" to HolidayName.NEW_YEAR,
            "Epiphany" to HolidayName.EPIPHANY,
            "Good Friday" to HolidayName.GOOD_FRIDAY,
            "Easter Sunday" to HolidayName.EASTER_SUNDAY,
            "Easter Monday" to HolidayName.EASTER_MONDAY,
            "Labor Day" to HolidayName.LABOUR_DAY,
            "Ascension Day" to HolidayName.ASCENSION,
            "Pentecost" to HolidayName.WHIT_SUNDAY,
            "Pentecost Monday" to HolidayName.WHIT_MONDAY,
            "Corpus Christi" to HolidayName.CORPUS_CHRISTI,
            "Assumption Day" to HolidayName.ASSUMPTION,
            "Assumption Of Mary Day" to HolidayName.ASSUMPTION,
            "All Saints' Day" to HolidayName.ALL_SAINTS,
            "Immaculate Conception" to HolidayName.IMMACULATE_CONCEPTION,
            "Christmas Day" to HolidayName.CHRISTMAS,
            "Saint Stephen's Day" to HolidayName.ST_STEPHEN,
            "Second Day of Christmas" to HolidayName.SECOND_CHRISTMAS_DAY,
            "Boxing Day" to HolidayName.BOXING_DAY,
            "National Day" to HolidayName.NATIONAL_DAY,
            "Victory Day" to HolidayName.VICTORY_1945,
            "Armistice Day" to HolidayName.ARMISTICE_1918,
            "Europe Day" to HolidayName.EUROPE_DAY,
            "German Unity Day" to HolidayName.GERMAN_UNITY_DAY,
            "Reformation Day" to HolidayName.REFORMATION_DAY,
            "Repentance and Prayer Day" to HolidayName.REPENTANCE_DAY,
            "Liberation Day" to HolidayName.LIBERATION_DAY,
            "Republic Day" to HolidayName.REPUBLIC_DAY,
            "Saint Francis of Assisi, Patron Saint of Italy" to HolidayName.ST_FRANCIS,
            "National Unity Day" to HolidayName.NATIONAL_UNITY_DAY,
            "Anniversary of the Unification of Italy" to HolidayName.ITALIAN_UNIFICATION,
            "Constitution Day" to HolidayName.CONSTITUTION_DAY,
            "May Day" to HolidayName.EARLY_MAY_BANK_HOLIDAY,
            "Spring Bank Holiday" to HolidayName.SPRING_BANK_HOLIDAY,
            "Millennium Celebrations" to HolidayName.SPECIAL_BANK_HOLIDAY,
            "Golden Jubilee of Elizabeth II" to HolidayName.SPECIAL_BANK_HOLIDAY,
            "Wedding of William and Catherine" to HolidayName.SPECIAL_BANK_HOLIDAY,
            "Diamond Jubilee of Elizabeth II" to HolidayName.SPECIAL_BANK_HOLIDAY,
            "Platinum Jubilee of Elizabeth II" to HolidayName.SPECIAL_BANK_HOLIDAY,
            "State Funeral of Queen Elizabeth II" to HolidayName.SPECIAL_BANK_HOLIDAY,
            "Coronation of Charles III" to HolidayName.SPECIAL_BANK_HOLIDAY,
            "Martin Luther King Jr. Day" to HolidayName.MARTIN_LUTHER_KING_DAY,
            "Washington's Birthday" to HolidayName.WASHINGTONS_BIRTHDAY,
            "Memorial Day" to HolidayName.MEMORIAL_DAY,
            "Juneteenth National Independence Day" to HolidayName.JUNETEENTH,
            "Independence Day" to HolidayName.INDEPENDENCE_DAY,
            "Columbus Day" to HolidayName.COLUMBUS_DAY,
            "Veterans Day" to HolidayName.VETERANS_DAY,
            "Thanksgiving Day" to HolidayName.THANKSGIVING,
        )

        /** `FR 2026-07-14 - National Day`: the country, then the holiday it names. */
        val REFERENCE: List<Pair<HolidayCountry, Holiday>> =
            checkNotNull(HolidaysTest::class.java.getResource("/holidays/reference-1990-2060.txt"))
                .readText(Charsets.UTF_8)
                .lines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { line ->
                    val (code, date, observed, label) = line.split(" ", limit = 4)
                    val country = HolidayCountry.valueOf(code)
                    val plain = label.removeSuffix(" (observed)")
                    // The reference calls the American one "Labor Day" too; ours is its own holiday.
                    val name = if (country == HolidayCountry.US && plain == "Labor Day") {
                        HolidayName.LABOR_DAY_US
                    } else {
                        checkNotNull(REFERENCE_NAMES[plain]) { "No name for '$plain' in: $line" }
                    }
                    country to Holiday(LocalDate.parse(date), name, observed = observed == "O")
                }
    }
}
