package com.filestech.agenda_tech.ui.screens.calendars

import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.domain.model.Calendar
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A calendar's line: the line edits it, the switch shows or hides its events — two targets, and the
 * switch named after the calendar. TalkBack said only "Switch, on" (2026-10-22).
 */
@RunWith(AndroidJUnit4::class)
class CalendarRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun theSwitch_isNamedAfterItsCalendar_andStaysApartFromTheLine() {
        val visibility = mutableListOf<Boolean>()
        var edits = 0
        compose.setContent {
            CalendarRow(
                calendar = Calendar(id = 7L, name = NAME, isVisible = true),
                onToggleVisibility = { visibility += it },
                onClick = { edits++ },
            )
        }
        val showEvents = resources.getString(R.string.calendars_show_events, NAME)

        compose.onNodeWithContentDescription(showEvents).assertIsToggleable().assertIsOn().performClick()
        compose.onNodeWithText(NAME).performClick()

        assertThat(visibility).containsExactly(false)
        assertThat(edits).isEqualTo(1)
    }

    private companion object {
        const val NAME = "Club"
    }
}
