package com.filestech.agenda_tech.ui.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * A line ending with a switch, where the whole line toggles — its label too — and reads to TalkBack as
 * one switch, with its state.
 *
 * Each screen used to draw its own, two ways: the editor's "All day" and the automatic backup toggled from
 * the switch alone, their label did nothing (seen on the S9, 2026-10-13); the settings' lines were
 * clickable around a switch focusable on its own, which TalkBack read twice, the line without its state.
 * `SwitchLineIsTheOnlySeamTest` keeps a new line from drawing its own switch again.
 *
 * [content] is what precedes the switch; give it the line's free width with `Modifier.weight(1f)`.
 * [contentPadding] lies inside the line, so a tap on it toggles as well.
 */
@Composable
fun SwitchLine(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        // No handler of its own, so none of the 48 dp a switch takes with one: given back, the line keeps
        // the height it had.
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.minimumInteractiveComponentSize(),
        )
    }
}
