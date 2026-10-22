package com.filestech.agenda_tech.ui.util

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.domain.model.CalendarColor
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The colour swatches: each one names its colour and says whether it is chosen, and the last one can
 * be reached in a narrow row — the settings' copy did not scroll, and graphite was out of reach on the
 * S9 (2026-10-22).
 */
@RunWith(AndroidJUnit4::class)
class ColorSwatchesTest {

    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private fun name(color: CalendarColor) = resources.getString(color.nameRes)

    @Test
    fun everySwatch_namesItsColour_andSaysWhichIsChosen() {
        compose.setContent {
            ColorSwatches(selected = CalendarColor.BASIL, onSelect = {}, modifier = Modifier.width(ROOMY))
        }

        CalendarColor.entries.forEach { color ->
            val swatch = compose.onNodeWithContentDescription(name(color))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            if (color == CalendarColor.BASIL) swatch.assertIsSelected() else swatch.assertIsNotSelected()
        }
        // Eleven names, eleven colours: two swatches saying the same name would not tell them apart.
        assertThat(CalendarColor.entries.map(::name).toSet()).hasSize(CalendarColor.entries.size)
    }

    @Test
    fun theLastColour_isReachedInANarrowRow_andChosen() {
        var chosen: CalendarColor? = null
        compose.setContent {
            ColorSwatches(selected = null, onSelect = { chosen = it }, modifier = Modifier.width(NARROW))
        }

        compose.onNodeWithContentDescription(name(CalendarColor.GRAPHITE))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertThat(chosen).isEqualTo(CalendarColor.GRAPHITE)
    }

    @Test
    fun theCalendarsColour_isOffered_onlyWhenAsked_andChosenWhenNoColourIs() {
        val calendarColour = resources.getString(R.string.color_name_calendar)
        compose.setContent {
            var selected by remember { mutableStateOf<CalendarColor?>(CalendarColor.TOMATO) }
            ColorSwatches(
                selected = selected,
                onSelect = { selected = it },
                modifier = Modifier.width(ROOMY),
                onSelectCalendarColor = { selected = null },
            )
        }

        compose.onNodeWithContentDescription(calendarColour).assertIsNotSelected().performClick()

        compose.onNodeWithContentDescription(calendarColour).assertIsSelected()
        compose.onNodeWithContentDescription(name(CalendarColor.TOMATO)).assertIsNotSelected()
    }

    @Test
    fun withoutTheCalendarsColour_noSuchSwatch() {
        compose.setContent {
            ColorSwatches(selected = CalendarColor.TOMATO, onSelect = {}, modifier = Modifier.width(ROOMY))
        }

        compose.onNodeWithContentDescription(resources.getString(R.string.color_name_calendar)).assertDoesNotExist()
    }

    private companion object {
        val ROOMY = 600.dp
        val NARROW = 200.dp
    }
}
