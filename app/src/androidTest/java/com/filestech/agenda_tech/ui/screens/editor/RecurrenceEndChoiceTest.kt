package com.filestech.agenda_tech.ui.screens.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The ways a series ends ("Never", "After", "On"): a tap on the label did nothing, only the button
 * answered (found with the all-day switch, 2026-10-22).
 */
@RunWith(AndroidJUnit4::class)
class RecurrenceEndChoiceTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aTapOnTheLabel_selectsTheChoice() {
        compose.setContent {
            var selected by remember { mutableStateOf(false) }
            RecurrenceEndChoice(selected = selected, label = LABEL, onSelect = { selected = true })
        }
        compose.onNodeWithText(LABEL).assertIsNotSelected()

        compose.onNodeWithText(LABEL, useUnmergedTree = true).performClick()

        compose.onNodeWithText(LABEL)
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertHeightIsAtLeast(48.dp)
    }

    private companion object {
        const val LABEL = "Never"
    }
}
