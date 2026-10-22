package com.filestech.agenda_tech.ui.screens.month

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.domain.holiday.Holiday
import com.filestech.agenda_tech.domain.holiday.HolidayName
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import kotlin.math.abs

/**
 * The day's list for a day with no events, in the free space under the dots grid: a public holiday with
 * no event floated halfway down that space, far from its date (seen on the S9, 2026-10-13). It now
 * opens the list, "No events" right under it; a day with neither keeps its message centred.
 */
@RunWith(AndroidJUnit4::class)
class EmptyDayHolidayTest {

    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val noEvents = resources.getString(R.string.month_no_events)
    private val armistice = resources.getString(R.string.holiday_armistice_1918)
    private val locale = resources.configuration.locales[0]

    private fun showEmptyDay(holidays: List<Holiday>) {
        compose.setContent {
            SelectedDayOccurrences(
                occurrences = emptyList(),
                holidays = holidays,
                locale = locale,
                onOccurrenceClick = { _, _ -> },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FREE_SPACE)
                    .testTag(LIST),
            )
        }
    }

    @Test
    fun aHolidayWithoutEvents_opensTheList_withNoEventsRightUnderIt() {
        showEmptyDay(listOf(Holiday(LocalDate.of(2026, 11, 11), HolidayName.ARMISTICE_1918)))

        val list = compose.onNodeWithTag(LIST).getBoundsInRoot()
        val holiday = compose.onNodeWithText(armistice).getBoundsInRoot()
        val message = compose.onNodeWithText(noEvents).getBoundsInRoot()

        assertThat((holiday.top - list.top).value).isLessThan(NEAR.value)
        assertThat((message.top - holiday.bottom).value).isLessThan(NEAR.value)
        assertThat(message.top.value).isGreaterThan(holiday.bottom.value)
    }

    @Test
    fun aDayWithNeither_keepsItsMessageCentred() {
        showEmptyDay(emptyList())

        val list = compose.onNodeWithTag(LIST).getBoundsInRoot()
        val message = compose.onNodeWithText(noEvents).getBoundsInRoot()
        val listMiddle = (list.top + list.bottom) / 2
        val messageMiddle = (message.top + message.bottom) / 2

        assertThat(abs((messageMiddle - listMiddle).value)).isLessThan(2f)
    }

    private companion object {
        const val LIST = "dayList"
        val FREE_SPACE = 480.dp
        val NEAR = 48.dp
    }
}
