package com.filestech.agenda_tech.domain.settings

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The month display setting: the order is the pinch's order, and the raw values are what DataStore
 * holds — both are behaviour, not decoration.
 */
class MonthDisplayTest {

    @Test
    fun `stored values never change meaning`() {
        // Renumbering would silently switch every installed app to another display after an update.
        assertThat(MonthDisplay.DOTS.rawValue).isEqualTo(0)
        assertThat(MonthDisplay.TITLES.rawValue).isEqualTo(1)
        assertThat(MonthDisplay.ROWS.rawValue).isEqualTo(2)
    }

    @Test
    fun `a stored value reads back as the same display`() {
        MonthDisplay.entries.forEach { assertThat(MonthDisplay.fromRaw(it.rawValue)).isEqualTo(it) }
    }

    @Test
    fun `an unknown stored value falls back to the dots`() {
        // A value written by a newer version, then read by an older one after a downgrade.
        assertThat(MonthDisplay.fromRaw(99)).isEqualTo(MonthDisplay.DOTS)
    }

    @Test
    fun `spreading the fingers goes towards more detail and stops at the rows`() {
        assertThat(MonthDisplay.DOTS.moreDetail()).isEqualTo(MonthDisplay.TITLES)
        assertThat(MonthDisplay.TITLES.moreDetail()).isEqualTo(MonthDisplay.ROWS)
        assertThat(MonthDisplay.ROWS.moreDetail()).isEqualTo(MonthDisplay.ROWS)
    }

    @Test
    fun `pinching goes towards less detail and stops at the dots`() {
        assertThat(MonthDisplay.ROWS.lessDetail()).isEqualTo(MonthDisplay.TITLES)
        assertThat(MonthDisplay.TITLES.lessDetail()).isEqualTo(MonthDisplay.DOTS)
        assertThat(MonthDisplay.DOTS.lessDetail()).isEqualTo(MonthDisplay.DOTS)
    }

    @Test
    fun `the default is the dots, so an update changes nothing on screen`() {
        assertThat(AppSettings().monthDisplay).isEqualTo(MonthDisplay.DOTS)
    }
}
