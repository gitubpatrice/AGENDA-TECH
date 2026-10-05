package com.filestech.agenda_tech.ui.screens.month

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.domain.settings.MonthDisplay

/**
 * The month display chooser, in the top bar.
 *
 * Its icon is a small drawing of the current display, so the bar always says how the month is drawn;
 * the menu names each choice in words, because three tiny grids are not self-explanatory. The last
 * line teaches the pinch — a gesture nobody guesses, offered here where someone already looking for
 * another display will read it.
 */
@Composable
internal fun MonthDisplayMenu(current: MonthDisplay, onSelect: (MonthDisplay) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = current.icon,
                contentDescription = stringResource(R.string.month_display_button, stringResource(current.labelRes)),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MonthDisplay.entries.forEach { display ->
                val isCurrent = display == current
                DropdownMenuItem(
                    text = { Text(stringResource(display.labelRes)) },
                    leadingIcon = { Icon(display.icon, contentDescription = null) },
                    trailingIcon = if (isCurrent) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelect(display)
                    },
                    modifier = Modifier.semantics { selected = isCurrent },
                )
            }
            HorizontalDivider()
            Text(
                text = stringResource(R.string.month_display_pinch_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .widthIn(max = HINT_MAX_WIDTH)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@get:StringRes
internal val MonthDisplay.labelRes: Int
    get() = when (this) {
        MonthDisplay.DOTS -> R.string.month_display_dots
        MonthDisplay.TITLES -> R.string.month_display_titles
        MonthDisplay.ROWS -> R.string.month_display_rows
    }

internal val MonthDisplay.icon: ImageVector
    get() = when (this) {
        MonthDisplay.DOTS -> DotsIcon
        MonthDisplay.TITLES -> TitlesIcon
        MonthDisplay.ROWS -> RowsIcon
    }

private val HINT_MAX_WIDTH = 240.dp

// Each icon is a calendar frame with a miniature of what the display puts inside it: dots, short
// titles in a grid, or one line per day. Drawn here rather than borrowed from the Material set, whose
// nearest shapes (a bulleted list, a grid of squares) already stand for the Agenda and Month tabs.

private val DotsIcon: ImageVector by lazy {
    displayIcon("MonthDots") {
        for (cy in listOf(10f, 15f)) for (cx in listOf(8f, 12f, 16f)) circle(cx, cy, 1.4f)
    }
}

private val TitlesIcon: ImageVector by lazy {
    displayIcon("MonthTitles") {
        for (top in listOf(8.4f, 13.6f)) {
            rect(6f, top, 11.2f, top + 1.6f)
            rect(12.8f, top, 18f, top + 1.6f)
        }
    }
}

private val RowsIcon: ImageVector by lazy {
    displayIcon("MonthRows") {
        for (top in listOf(7.8f, 11.2f, 14.6f)) {
            rect(6f, top, 7.6f, top + 1.6f)
            rect(9f, top, 18f, top + 1.6f)
        }
    }
}

private fun displayIcon(name: String, inside: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
        .path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(5.5f, 4.5f)
            horizontalLineTo(18.5f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = 2f, dy1 = 2f)
            verticalLineTo(17.5f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = -2f, dy1 = 2f)
            horizontalLineTo(5.5f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = -2f, dy1 = -2f)
            verticalLineTo(6.5f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = 2f, dy1 = -2f)
            close()
        }
        .path(fill = SolidColor(Color.Black), pathBuilder = inside)
        .build()

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 2 * r, dy1 = 0f)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = -2 * r, dy1 = 0f)
    close()
}

private fun PathBuilder.rect(left: Float, top: Float, right: Float, bottom: Float) {
    moveTo(left, top)
    horizontalLineTo(right)
    verticalLineTo(bottom)
    horizontalLineTo(left)
    close()
}
