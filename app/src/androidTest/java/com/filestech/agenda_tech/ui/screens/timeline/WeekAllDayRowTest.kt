package com.filestech.agenda_tech.ui.screens.timeline

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.agenda_tech.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The week's all-day row past three events a day: its "+N" said how many more and did nothing
 * (2026-10-13). A tap on it, or on the arrow in the hours' gutter, unfolds the row; the arrow folds it back.
 */
@RunWith(AndroidJUnit4::class)
class WeekAllDayRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val expand = resources.getString(R.string.week_all_day_expand)
    private val collapse = resources.getString(R.string.week_all_day_collapse)
    private val twoMore = resources.getQuantityString(R.plurals.month_more_events, 2, 2)

    private fun showWeek(eventsPerDay: List<Int>) {
        compose.setContent {
            var expanded by remember { mutableStateOf(false) }
            WeekAllDayRow(
                days = week(eventsPerDay),
                expanded = expanded,
                onExpandedChange = { expanded = it },
                onItemClick = { _, _ -> },
            )
        }
    }

    @Test
    fun aTapOnTheCount_unfoldsTheRow_andTheArrowFoldsItBack() {
        showWeek(listOf(5, 1, 0, 0, 0, 0, 0))
        compose.onNodeWithText(title(day = 0, index = 3)).assertDoesNotExist()

        compose.onNodeWithContentDescription(twoMore).performClick()

        (0 until 5).forEach { compose.onNodeWithText(title(day = 0, index = it)).assertExists() }
        compose.onNodeWithContentDescription(twoMore).assertDoesNotExist()

        compose.onNodeWithContentDescription(collapse).performClick()

        compose.onNodeWithText(title(day = 0, index = 3)).assertDoesNotExist()
        compose.onNodeWithText(title(day = 0, index = 2)).assertExists()
        compose.onNodeWithContentDescription(twoMore).assertExists()
    }

    @Test
    fun theArrow_unfoldsTheRow_too() {
        showWeek(listOf(0, 0, 4, 0, 0, 0, 5))

        compose.onNodeWithContentDescription(expand).performClick()

        compose.onNodeWithText(title(day = 2, index = 3)).assertExists()
        compose.onNodeWithText(title(day = 6, index = 4)).assertExists()
        compose.onNodeWithContentDescription(expand).assertDoesNotExist()
    }

    @Test
    fun noArrow_whenEveryDayFits() {
        showWeek(listOf(3, 3, 1, 0, 0, 0, 2))

        compose.onNodeWithContentDescription(expand).assertDoesNotExist()
        compose.onNodeWithContentDescription(collapse).assertDoesNotExist()
        compose.onNodeWithText(title(day = 0, index = 2)).assertExists()
    }

    @Test
    fun theCount_isAButton_largeEnoughToAimAt() {
        showWeek(listOf(5, 0, 0, 0, 0, 0, 0))

        compose.onNodeWithContentDescription(twoMore)
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(24.dp)
    }

    private fun week(eventsPerDay: List<Int>): List<DayTimelineData> =
        eventsPerDay.mapIndexed { day, count ->
            val date = MONDAY.plusDays(day.toLong())
            DayTimelineData(
                date = date,
                allDayItems = (0 until count).map { index ->
                    TimelineItem(
                        eventId = day * 100L + index,
                        title = title(day, index),
                        startUtcMillis = date.toEpochDay() * DAY_MILLIS,
                        endUtcMillis = (date.toEpochDay() + 1) * DAY_MILLIS,
                        occurrenceStartUtcMillis = date.toEpochDay() * DAY_MILLIS,
                        allDay = true,
                        colorArgb = COLOR,
                    )
                },
                positioned = emptyList(),
                isToday = false,
                nowMinute = null,
            )
        }

    private fun title(day: Int, index: Int) = "D$day E$index"

    private companion object {
        val MONDAY: LocalDate = LocalDate.of(2026, 11, 9)
        const val DAY_MILLIS = 86_400_000L
        const val COLOR = 0xFF1E88E5.toInt()
    }
}
