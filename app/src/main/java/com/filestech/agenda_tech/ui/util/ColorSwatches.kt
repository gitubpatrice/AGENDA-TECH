package com.filestech.agenda_tech.ui.util

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.domain.model.CalendarColor

/**
 * The colour swatches — the editor's, the default colour in the settings, a calendar's — as one group of
 * choices: each swatch says its colour and whether it is the chosen one, and the row scrolls.
 *
 * Three screens drew their own copy, and the copies had drifted: the settings' row did not scroll, so
 * on the S9 lavender showed as a sliver and graphite not at all — neither could be made the default
 * colour (2026-10-22). And no swatch had a name: TalkBack read eleven unlabelled buttons, with nothing to
 * say which was chosen. `ColorSwatchesIsTheOnlySeamTest` keeps a screen from drawing its own again.
 *
 * [onSelectCalendarColor], when given, puts first an outlined empty swatch: no colour of the event's own,
 * the calendar's instead (the editor's null override).
 */
@Composable
fun ColorSwatches(
    selected: CalendarColor?,
    onSelect: (CalendarColor) -> Unit,
    modifier: Modifier = Modifier,
    onSelectCalendarColor: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onSelectCalendarColor != null) {
            Swatch(
                fill = null,
                selected = selected == null,
                label = stringResource(R.string.color_name_calendar),
                onClick = onSelectCalendarColor,
            )
        }
        CalendarColor.entries.forEach { color ->
            Swatch(
                fill = Color(color.argb),
                selected = color == selected,
                label = stringResource(color.nameRes),
                onClick = { onSelect(color) },
            )
        }
    }
}

@Composable
private fun Swatch(fill: Color?, selected: Boolean, label: String, onClick: () -> Unit) {
    val ring = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .then(if (fill != null) Modifier.background(fill) else Modifier)
            .then(
                when {
                    selected -> Modifier.border(3.dp, ring, CircleShape)
                    fill == null -> Modifier.border(1.dp, ring, CircleShape)
                    else -> Modifier
                },
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
    )
}

/** The colour's name in plain words — what a screen reader says for its swatch. */
@get:StringRes
val CalendarColor.nameRes: Int
    get() = when (this) {
        CalendarColor.BLUEBERRY -> R.string.color_name_blueberry
        CalendarColor.PEACOCK -> R.string.color_name_peacock
        CalendarColor.BASIL -> R.string.color_name_basil
        CalendarColor.SAGE -> R.string.color_name_sage
        CalendarColor.BANANA -> R.string.color_name_banana
        CalendarColor.TANGERINE -> R.string.color_name_tangerine
        CalendarColor.TOMATO -> R.string.color_name_tomato
        CalendarColor.FLAMINGO -> R.string.color_name_flamingo
        CalendarColor.GRAPE -> R.string.color_name_grape
        CalendarColor.LAVENDER -> R.string.color_name_lavender
        CalendarColor.GRAPHITE -> R.string.color_name_graphite
    }
