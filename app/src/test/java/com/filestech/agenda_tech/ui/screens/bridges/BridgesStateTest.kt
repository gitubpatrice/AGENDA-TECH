package com.filestech.agenda_tech.ui.screens.bridges

import com.filestech.agenda_tech.domain.holiday.Bridges
import com.filestech.agenda_tech.domain.holiday.HolidayCountry
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Which year the screen opens on, and which of its bridges are still ahead (France 2026 and 2027). */
class BridgesStateTest {

    @Test
    fun `no country chosen - nothing to show, once the settings are read`() {
        val state = bridgesState(country = null, chosenYear = null, today = LocalDate.of(2027, 5, 1))

        assertThat(state.loaded).isTrue()
        assertThat(state.country).isNull()
        assertThat(state.bridges).isEmpty()
    }

    @Test
    fun `none left this year - opens on the next one, all of its bridges`() {
        // Christmas 2026 falls on a Friday: after the 11th of November, no bridge is left in 2026.
        val state = bridgesState(HolidayCountry.FR, chosenYear = null, today = LocalDate.of(2026, 11, 28))

        assertThat(state.years).containsExactly(2026, 2027).inOrder()
        assertThat(state.year).isEqualTo(2027)
        assertThat(state.bridges).isEqualTo(Bridges.inYear(HolidayCountry.FR, 2027))
    }

    @Test
    fun `this year - only the bridges still ahead, today's last day to take included`() {
        val onTheDay = bridgesState(HolidayCountry.FR, chosenYear = null, today = LocalDate.of(2027, 7, 13))
        val dayAfter = bridgesState(HolidayCountry.FR, chosenYear = null, today = LocalDate.of(2027, 7, 14))

        assertThat(onTheDay.year).isEqualTo(2027)
        assertThat(onTheDay.bridges.map { it.daysToTake.first() }).containsExactly(
            LocalDate.of(2027, 7, 12),
            LocalDate.of(2027, 7, 15),
            LocalDate.of(2027, 11, 12),
        ).inOrder()
        assertThat(dayAfter.bridges.map { it.daysToTake.first() }).containsExactly(
            LocalDate.of(2027, 7, 15),
            LocalDate.of(2027, 11, 12),
        ).inOrder()
    }

    @Test
    fun `the year chosen is kept, one outside the two offered is not`() {
        val today = LocalDate.of(2027, 7, 14)

        assertThat(bridgesState(HolidayCountry.FR, chosenYear = 2028, today = today).year).isEqualTo(2028)
        assertThat(bridgesState(HolidayCountry.FR, chosenYear = 2028, today = today).bridges)
            .isEqualTo(Bridges.inYear(HolidayCountry.FR, 2028))
        assertThat(bridgesState(HolidayCountry.FR, chosenYear = 2031, today = today).year).isEqualTo(2027)
    }
}
