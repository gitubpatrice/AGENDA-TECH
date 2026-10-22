package com.filestech.agenda_tech.ui.util

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The line with a switch (editor's "All day", automatic backup, settings): a tap on its label toggles, and
 * TalkBack meets one switch with its label and its state — the editor's and the backup's label did
 * nothing, the settings' line was read twice (2026-10-13).
 */
@RunWith(AndroidJUnit4::class)
class SwitchLineTest {

    @get:Rule
    val compose = createComposeRule()

    private val changes = mutableListOf<Boolean>()

    private fun showLine(enabled: Boolean = true) {
        compose.setContent {
            var checked by remember { mutableStateOf(false) }
            SwitchLine(
                checked = checked,
                onCheckedChange = {
                    changes += it
                    checked = it
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
            ) {
                Text(LABEL, modifier = Modifier.weight(1f))
            }
        }
    }

    @Test
    fun aTapOnTheLabel_togglesTheSwitch_bothWays() {
        showLine()

        // On the label itself, not the centre of the line: the label is what did nothing.
        compose.onNodeWithText(LABEL, useUnmergedTree = true).performClick()
        compose.onNodeWithText(LABEL).assertIsOn()
        compose.onNodeWithText(LABEL, useUnmergedTree = true).performClick()
        compose.onNodeWithText(LABEL).assertIsOff()

        assertThat(changes).containsExactly(true, false).inOrder()
    }

    @Test
    fun theLine_isOneSwitch_carryingItsLabel() {
        showLine()

        compose.onNodeWithText(LABEL)
            .assert(isToggleable())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        // One target, not a line around a switch that answers on its own.
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun disabled_aTapChangesNothing() {
        showLine(enabled = false)

        compose.onNodeWithText(LABEL, useUnmergedTree = true).performClick()

        assertThat(changes).isEmpty()
        compose.onNodeWithText(LABEL).assertIsNotEnabled().assertIsOff()
    }

    @Test
    fun theLine_keepsTheHeightTheSwitchGaveIt() {
        showLine()

        compose.onNodeWithText(LABEL).assertHeightIsAtLeast(48.dp)
    }

    private companion object {
        const val LABEL = "All day"
    }
}
