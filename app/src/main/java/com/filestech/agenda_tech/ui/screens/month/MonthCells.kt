package com.filestech.agenda_tech.ui.screens.month

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.agenda_tech.R
import java.util.Locale

// What the three month displays share: how a day number looks, how a day answers a tap and a long
// press, and what a screen reader says of it. Kept in one place so the displays cannot drift apart —
// a long press that creates an event in one display and does nothing in another is not a feature.

/**
 * Diameter of the day-number circle: [base] at the default text size, and wide enough for two digits
 * once the user enlarges text. Fixed, it cut the number at 200 %: the grid showed "1" for the 12th.
 */
@Composable
internal fun dayNumberDiameter(base: Dp, style: TextStyle): Dp =
    with(LocalDensity.current) { maxOf(base, style.fontSize.toDp() * CIRCLE_PER_FONT_SIZE) }

/** Width of the week-number column, for the same reason: two digits at any text size. */
@Composable
internal fun weekNumberWidth(): Dp =
    with(LocalDensity.current) { maxOf(WEEK_NUMBER_WIDTH, MaterialTheme.typography.labelSmall.fontSize.toDp() * 2.2f) }

/** The day number, on a filled circle when it is today. [size] is the diameter at the default text size. */
@Composable
internal fun DayNumber(cell: DayCellData, size: Dp, style: TextStyle) {
    val color = when {
        cell.isToday -> MaterialTheme.colorScheme.onPrimary
        !cell.isInMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = OUT_OF_MONTH_ALPHA)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(dayNumberDiameter(size, style))
            .clip(CircleShape)
            .then(if (cell.isToday) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier)
            // The cell around it announces the full date; a screen reader saying "6" on top adds nothing.
            .clearAndSetSemantics {},
    ) {
        Text(
            text = cell.date.dayOfMonth.toString(),
            style = style,
            fontWeight = if (cell.isToday) FontWeight.Bold else FontWeight.Normal,
            color = color,
        )
    }
}

/**
 * A coloured dot per event, at most [MAX_DOTS], then a "+" — the fifth event of a day used to vanish
 * without a sign, which reads as a day with four.
 */
@Composable
internal fun EventDots(events: List<OccurrenceData>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        events.take(MAX_DOTS).forEach { event ->
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(Color(event.colorArgb)),
            )
        }
        if (events.size > MAX_DOTS) {
            Text(
                text = "+",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // The count is already said with the date.
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/**
 * The selected day, outlined in [color] — the same mark in the three displays. A filled cell stacked
 * two blues under tinted titles and blue event bars (seen on the device), and in the dark theme it
 * became a saturated block across the rows.
 */
internal fun Modifier.selectedDayOutline(selected: Boolean, color: Color, shape: Shape): Modifier =
    if (selected) border(SELECTED_DAY_BORDER, color, shape) else this

/** ISO week number at the start of a grid row. */
@Composable
internal fun WeekNumberCell(weekNumber: Int, modifier: Modifier = Modifier) {
    Box(modifier = modifier.width(weekNumberWidth()), contentAlignment = Alignment.TopCenter) {
        Text(
            text = weekNumber.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * A tap selects the day; a long press creates an event on it. [longClickLabel] is what a screen
 * reader announces for the long press, so the shortcut is not reserved to sighted users.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.dayGestures(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    longClickLabel: String,
): Modifier = combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = longClickLabel)

/**
 * What a screen reader says of a day: its full date, and — when the display shows no titles — how
 * many events it holds. A bare "6" said nothing of either.
 */
@Composable
internal fun dayDescription(cell: DayCellData, locale: Locale, withCount: Boolean): String {
    val date = dayLabel(cell.date, locale)
    val count = cell.events.size
    if (!withCount || count == 0) return date
    return date + ", " + pluralStringResource(R.plurals.month_day_events, count, count)
}

internal const val MAX_DOTS = 4

/** Days of the neighbouring months are drawn faded, as on a paper calendar. */
internal const val OUT_OF_MONTH_ALPHA = 0.4f

/**
 * Their event titles are faded less: they are information, and at 0.4 they fell to a contrast of
 * 2.5:1 (light theme) — under any readability floor. At 0.65 they reach about 5.3:1 and still read as
 * belonging to another month (pre-release audit, 2026-10-05).
 */
internal const val OUT_OF_MONTH_TITLE_ALPHA = 0.65f

private val WEEK_NUMBER_WIDTH = 24.dp
private val SELECTED_DAY_BORDER = 2.dp

/**
 * Two digits fit in a circle 1.7 times the font size: their width is about 1.1 em, and the square
 * inscribed in such a circle measures 1.2 em. At the default size the base diameters (22 and 26 dp)
 * are larger, so nothing changes there.
 */
private const val CIRCLE_PER_FONT_SIZE = 1.7f
