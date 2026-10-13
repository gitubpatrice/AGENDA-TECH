package com.filestech.agenda_tech.domain.holiday

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.temporal.TemporalAdjusters

/**
 * The countries whose national public holidays the agenda can show, and the first year each is shown
 * for: the rules below are checked, holiday by holiday, against an independent reference from that year
 * to 2060 (HolidaysTest). Before it, history the rules do not cover — Germany before its reunification,
 * Spain's yearly lists before 2008 — so nothing is shown rather than something unchecked.
 *
 * National only: a region's own holidays (a German Land, a Swiss canton, Alsace-Moselle, Scotland, a
 * Spanish community) are not shown. For the United Kingdom that leaves the holidays all four nations
 * share; for Spain, a national holiday falling on a Sunday is not one that year — each community decides
 * whether to move it.
 *
 * The enum's name is the ISO 3166 code: `Locale("", code)` gives the country's name in any language.
 */
enum class HolidayCountry(val firstYear: Int) {
    FR(1990),
    BE(1990),
    CH(1990),
    LU(1990),
    DE(1991),
    AT(1990),
    IT(1990),
    ES(2008),
    GB(1990),
    US(1990),
}

/** What a holiday is called — named in the app's language by the screen (see HolidayNames). */
enum class HolidayName {
    NEW_YEAR,
    EPIPHANY,
    GOOD_FRIDAY,
    EASTER_SUNDAY,
    EASTER_MONDAY,
    LABOUR_DAY,
    ASCENSION,
    WHIT_SUNDAY,
    WHIT_MONDAY,
    CORPUS_CHRISTI,
    ASSUMPTION,
    ALL_SAINTS,
    IMMACULATE_CONCEPTION,
    CHRISTMAS,
    ST_STEPHEN,
    SECOND_CHRISTMAS_DAY,
    BOXING_DAY,
    NATIONAL_DAY,
    VICTORY_1945,
    ARMISTICE_1918,
    EUROPE_DAY,
    GERMAN_UNITY_DAY,
    REFORMATION_DAY,
    REPENTANCE_DAY,
    LIBERATION_DAY,
    REPUBLIC_DAY,
    ST_FRANCIS,
    NATIONAL_UNITY_DAY,
    ITALIAN_UNIFICATION,
    CONSTITUTION_DAY,
    EARLY_MAY_BANK_HOLIDAY,
    SPRING_BANK_HOLIDAY,
    SPECIAL_BANK_HOLIDAY,
    MARTIN_LUTHER_KING_DAY,
    WASHINGTONS_BIRTHDAY,
    MEMORIAL_DAY,
    JUNETEENTH,
    INDEPENDENCE_DAY,
    LABOR_DAY_US,
    COLUMBUS_DAY,
    VETERANS_DAY,
    THANKSGIVING,
}

/**
 * One public holiday. [observed] marks the day off that stands in for a holiday falling on a weekend —
 * the Monday after a British Christmas on a Saturday, the Friday before an American Fourth of July on a
 * Saturday.
 */
data class Holiday(val date: LocalDate, val name: HolidayName, val observed: Boolean = false)

/**
 * National public holidays, computed on the phone: no list to download, nothing to keep up to date
 * except these rules. Pure — no Android — hence unit-testable.
 */
object Holidays {

    /** The holidays of [country] from [from] included to [until] excluded, by date. */
    fun between(country: HolidayCountry, from: LocalDate, until: LocalDate): List<Holiday> {
        if (!from.isBefore(until)) return emptyList()
        // One year either side: a day off standing in for a holiday can fall in the year next to it — an
        // American New Year's Day on a Saturday is taken on the 31st of December before.
        return ((from.year - 1)..(until.year + 1))
            .flatMap { year -> inYear(country, year) }
            .filter { !it.date.isBefore(from) && it.date.isBefore(until) }
            .sortedWith(compareBy({ it.date }, { it.name.ordinal }))
    }

    /** The holidays of [country]'s year [year], with the days off standing in for them. */
    internal fun inYear(country: HolidayCountry, year: Int): List<Holiday> {
        if (year < country.firstYear) return emptyList()
        val days = Year(year)
        when (country) {
            HolidayCountry.FR -> days.france()
            HolidayCountry.BE -> days.belgium()
            HolidayCountry.CH -> days.switzerland()
            HolidayCountry.LU -> days.luxembourg()
            HolidayCountry.DE -> days.germany()
            HolidayCountry.AT -> days.austria()
            HolidayCountry.IT -> days.italy()
            HolidayCountry.ES -> days.spain()
            HolidayCountry.GB -> days.unitedKingdom()
            HolidayCountry.US -> days.unitedStates()
        }
        return days.holidays
    }

    /** Easter Sunday of the Gregorian calendar (the anonymous algorithm, Meeus/Jones/Butcher). */
    fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    private fun Year.france() {
        on(1, 1, HolidayName.NEW_YEAR)
        fromEaster(1, HolidayName.EASTER_MONDAY)
        on(5, 1, HolidayName.LABOUR_DAY)
        on(5, 8, HolidayName.VICTORY_1945)
        fromEaster(39, HolidayName.ASCENSION)
        // 2005 to 2007: the "day of solidarity" took Whit Monday's place as a working day.
        if (year !in 2005..2007) fromEaster(50, HolidayName.WHIT_MONDAY)
        on(7, 14, HolidayName.NATIONAL_DAY)
        on(8, 15, HolidayName.ASSUMPTION)
        on(11, 1, HolidayName.ALL_SAINTS)
        on(11, 11, HolidayName.ARMISTICE_1918)
        on(12, 25, HolidayName.CHRISTMAS)
    }

    private fun Year.belgium() {
        on(1, 1, HolidayName.NEW_YEAR)
        fromEaster(0, HolidayName.EASTER_SUNDAY)
        fromEaster(1, HolidayName.EASTER_MONDAY)
        on(5, 1, HolidayName.LABOUR_DAY)
        fromEaster(39, HolidayName.ASCENSION)
        fromEaster(49, HolidayName.WHIT_SUNDAY)
        fromEaster(50, HolidayName.WHIT_MONDAY)
        on(7, 21, HolidayName.NATIONAL_DAY)
        on(8, 15, HolidayName.ASSUMPTION)
        on(11, 1, HolidayName.ALL_SAINTS)
        on(11, 11, HolidayName.ARMISTICE_1918)
        on(12, 25, HolidayName.CHRISTMAS)
    }

    /** The federal holiday and the three every canton keeps; the rest is cantonal. */
    private fun Year.switzerland() {
        on(1, 1, HolidayName.NEW_YEAR)
        fromEaster(39, HolidayName.ASCENSION)
        on(8, 1, HolidayName.NATIONAL_DAY)
        on(12, 25, HolidayName.CHRISTMAS)
    }

    private fun Year.luxembourg() {
        on(1, 1, HolidayName.NEW_YEAR)
        fromEaster(1, HolidayName.EASTER_MONDAY)
        on(5, 1, HolidayName.LABOUR_DAY)
        // Law of 26 April 2019.
        if (year >= 2019) on(5, 9, HolidayName.EUROPE_DAY)
        fromEaster(39, HolidayName.ASCENSION)
        fromEaster(50, HolidayName.WHIT_MONDAY)
        on(6, 23, HolidayName.NATIONAL_DAY)
        on(8, 15, HolidayName.ASSUMPTION)
        on(11, 1, HolidayName.ALL_SAINTS)
        on(12, 25, HolidayName.CHRISTMAS)
        on(12, 26, HolidayName.ST_STEPHEN)
    }

    private fun Year.germany() {
        on(1, 1, HolidayName.NEW_YEAR)
        fromEaster(-2, HolidayName.GOOD_FRIDAY)
        fromEaster(1, HolidayName.EASTER_MONDAY)
        on(5, 1, HolidayName.LABOUR_DAY)
        fromEaster(39, HolidayName.ASCENSION)
        fromEaster(50, HolidayName.WHIT_MONDAY)
        on(10, 3, HolidayName.GERMAN_UNITY_DAY)
        // Nationwide once, for the 500th anniversary of the Reformation.
        if (year == 2017) on(10, 31, HolidayName.REFORMATION_DAY)
        // Nationwide until 1994: the Wednesday before the 23rd of November.
        if (year <= 1994) {
            add(LocalDate.of(year, 11, 22).with(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY)), HolidayName.REPENTANCE_DAY)
        }
        on(12, 25, HolidayName.CHRISTMAS)
        on(12, 26, HolidayName.SECOND_CHRISTMAS_DAY)
    }

    private fun Year.austria() {
        on(1, 1, HolidayName.NEW_YEAR)
        on(1, 6, HolidayName.EPIPHANY)
        fromEaster(1, HolidayName.EASTER_MONDAY)
        on(5, 1, HolidayName.LABOUR_DAY)
        fromEaster(39, HolidayName.ASCENSION)
        fromEaster(50, HolidayName.WHIT_MONDAY)
        fromEaster(60, HolidayName.CORPUS_CHRISTI)
        on(8, 15, HolidayName.ASSUMPTION)
        on(10, 26, HolidayName.NATIONAL_DAY)
        on(11, 1, HolidayName.ALL_SAINTS)
        on(12, 8, HolidayName.IMMACULATE_CONCEPTION)
        on(12, 25, HolidayName.CHRISTMAS)
        on(12, 26, HolidayName.ST_STEPHEN)
    }

    private fun Year.italy() {
        on(1, 1, HolidayName.NEW_YEAR)
        on(1, 6, HolidayName.EPIPHANY)
        // Once, for the 150th anniversary of the unification (Law 47 of 2011).
        if (year == 2011) on(3, 17, HolidayName.ITALIAN_UNIFICATION)
        fromEaster(0, HolidayName.EASTER_SUNDAY)
        fromEaster(1, HolidayName.EASTER_MONDAY)
        on(4, 25, HolidayName.LIBERATION_DAY)
        on(5, 1, HolidayName.LABOUR_DAY)
        // Moved to the first Sunday of June from 1977, back to the 2nd of June in 2001.
        if (year <= 2000) {
            add(firstOf(Month.JUNE, DayOfWeek.SUNDAY), HolidayName.REPUBLIC_DAY)
        } else {
            on(6, 2, HolidayName.REPUBLIC_DAY)
        }
        on(8, 15, HolidayName.ASSUMPTION)
        // Law 151 of 8 October 2025.
        if (year >= 2026) on(10, 4, HolidayName.ST_FRANCIS)
        on(11, 1, HolidayName.ALL_SAINTS)
        // On the first Sunday of November since 1977.
        add(firstOf(Month.NOVEMBER, DayOfWeek.SUNDAY), HolidayName.NATIONAL_UNITY_DAY)
        on(12, 8, HolidayName.IMMACULATE_CONCEPTION)
        on(12, 25, HolidayName.CHRISTMAS)
        on(12, 26, HolidayName.ST_STEPHEN)
    }

    /** A national holiday falling on a Sunday is not national that year: each community may move it. */
    private fun Year.spain() {
        unlessSunday(1, 1, HolidayName.NEW_YEAR)
        unlessSunday(1, 6, HolidayName.EPIPHANY)
        fromEaster(-2, HolidayName.GOOD_FRIDAY)
        unlessSunday(5, 1, HolidayName.LABOUR_DAY)
        unlessSunday(8, 15, HolidayName.ASSUMPTION)
        unlessSunday(10, 12, HolidayName.NATIONAL_DAY)
        unlessSunday(11, 1, HolidayName.ALL_SAINTS)
        unlessSunday(12, 6, HolidayName.CONSTITUTION_DAY)
        unlessSunday(12, 8, HolidayName.IMMACULATE_CONCEPTION)
        unlessSunday(12, 25, HolidayName.CHRISTMAS)
    }

    /**
     * The bank holidays all four nations share (Easter Monday is not one in Scotland; the summer one
     * falls on different Mondays). A holiday on a weekend is taken on the next working day.
     */
    private fun Year.unitedKingdom() {
        on(1, 1, HolidayName.NEW_YEAR)
        fromEaster(-2, HolidayName.GOOD_FRIDAY)
        // Moved to the 8th of May for the 50th and the 75th anniversary of VE Day.
        if (year == 1995 || year == 2020) {
            on(5, 8, HolidayName.EARLY_MAY_BANK_HOLIDAY)
        } else {
            add(firstOf(Month.MAY, DayOfWeek.MONDAY), HolidayName.EARLY_MAY_BANK_HOLIDAY)
        }
        // Moved for the Golden, Diamond and Platinum Jubilees.
        when (year) {
            2002, 2012 -> on(6, 4, HolidayName.SPRING_BANK_HOLIDAY)
            2022 -> on(6, 2, HolidayName.SPRING_BANK_HOLIDAY)
            else -> add(lastOf(Month.MAY, DayOfWeek.MONDAY), HolidayName.SPRING_BANK_HOLIDAY)
        }
        on(12, 25, HolidayName.CHRISTMAS)
        on(12, 26, HolidayName.BOXING_DAY)
        // Granted once each by royal proclamation.
        UK_SPECIAL_DAYS.filter { it.year == year }.forEach { add(it, HolidayName.SPECIAL_BANK_HOLIDAY) }
        // After every holiday is placed, so a substitute never lands on one: a Christmas on Sunday is taken
        // on the Tuesday, Boxing Day being already the Monday.
        listOf(HolidayName.NEW_YEAR, HolidayName.CHRISTMAS, HolidayName.BOXING_DAY).forEach { name ->
            val actual = holidays.first { it.name == name && !it.observed }.date
            if (actual.dayOfWeek == DayOfWeek.SATURDAY || actual.dayOfWeek == DayOfWeek.SUNDAY) {
                var substitute = actual.plusDays(1)
                while (substitute.isWeekend() || holidays.any { it.date == substitute }) {
                    substitute = substitute.plusDays(1)
                }
                add(substitute, name, observed = true)
            }
        }
    }

    /** Federal holidays (5 U.S.C. 6103). One on a Saturday is taken on the Friday, on a Sunday the Monday. */
    private fun Year.unitedStates() {
        observedUs(LocalDate.of(year, 1, 1), HolidayName.NEW_YEAR)
        add(nthOf(Month.JANUARY, DayOfWeek.MONDAY, 3), HolidayName.MARTIN_LUTHER_KING_DAY)
        add(nthOf(Month.FEBRUARY, DayOfWeek.MONDAY, 3), HolidayName.WASHINGTONS_BIRTHDAY)
        add(lastOf(Month.MAY, DayOfWeek.MONDAY), HolidayName.MEMORIAL_DAY)
        // Juneteenth National Independence Day Act, 17 June 2021.
        if (year >= 2021) observedUs(LocalDate.of(year, 6, 19), HolidayName.JUNETEENTH)
        observedUs(LocalDate.of(year, 7, 4), HolidayName.INDEPENDENCE_DAY)
        add(firstOf(Month.SEPTEMBER, DayOfWeek.MONDAY), HolidayName.LABOR_DAY_US)
        add(nthOf(Month.OCTOBER, DayOfWeek.MONDAY, 2), HolidayName.COLUMBUS_DAY)
        observedUs(LocalDate.of(year, 11, 11), HolidayName.VETERANS_DAY)
        add(nthOf(Month.NOVEMBER, DayOfWeek.THURSDAY, 4), HolidayName.THANKSGIVING)
        observedUs(LocalDate.of(year, 12, 25), HolidayName.CHRISTMAS)
    }

    private fun Year.observedUs(date: LocalDate, name: HolidayName) {
        add(date, name)
        when (date.dayOfWeek) {
            DayOfWeek.SATURDAY -> add(date.minusDays(1), name, observed = true)
            DayOfWeek.SUNDAY -> add(date.plusDays(1), name, observed = true)
            else -> Unit
        }
    }

    private fun LocalDate.isWeekend(): Boolean = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY

    /** Coronations, jubilees, a royal wedding and funeral, the millennium: each a bank holiday once. */
    private val UK_SPECIAL_DAYS = listOf(
        LocalDate.of(1999, 12, 31),
        LocalDate.of(2002, 6, 3),
        LocalDate.of(2011, 4, 29),
        LocalDate.of(2012, 6, 5),
        LocalDate.of(2022, 6, 3),
        LocalDate.of(2022, 9, 19),
        LocalDate.of(2023, 5, 8),
    )

    /** The holidays of one year, as the country's rules add them. */
    private class Year(val year: Int) {
        private val easter: LocalDate = easterSunday(year)
        val holidays = mutableListOf<Holiday>()

        fun add(date: LocalDate, name: HolidayName, observed: Boolean = false) {
            holidays += Holiday(date, name, observed)
        }

        fun on(month: Int, day: Int, name: HolidayName) = add(LocalDate.of(year, month, day), name)

        fun unlessSunday(month: Int, day: Int, name: HolidayName) {
            val date = LocalDate.of(year, month, day)
            if (date.dayOfWeek != DayOfWeek.SUNDAY) add(date, name)
        }

        fun fromEaster(days: Long, name: HolidayName) = add(easter.plusDays(days), name)

        fun firstOf(month: Month, day: DayOfWeek): LocalDate =
            LocalDate.of(year, month, 1).with(TemporalAdjusters.firstInMonth(day))

        fun nthOf(month: Month, day: DayOfWeek, n: Int): LocalDate =
            LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, day))

        fun lastOf(month: Month, day: DayOfWeek): LocalDate =
            LocalDate.of(year, month, 1).with(TemporalAdjusters.lastInMonth(day))
    }
}
