package com.filestech.agenda_tech.ui.screens.month

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.domain.birthday.displayTitle
import com.filestech.agenda_tech.ui.util.LocalDeviceZone
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The month grid with the titles inside the cells. It fills the height it is given — six rows sharing
 * it — because the titles are the reason to pick this display, and every line of height is a line of
 * title.
 */
@Composable
internal fun MonthTitlesGrid(
    weeks: List<List<DayCellData>>,
    showWeekNumbers: Boolean,
    locale: Locale,
    onDayClick: (LocalDate) -> Unit,
    onDayLongClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val addLabel = stringResource(R.string.month_add_event)
    // One measurer for the whole grid rather than one per cell: 42 cells per page, three pages composed.
    val measurer = rememberTextMeasurer()
    Column(modifier = modifier) {
        weeks.forEach { week ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (showWeekNumbers) {
                    val weekNumber = week[MonthGrid.DAYS_PER_WEEK / 2].date.get(WeekFields.ISO.weekOfWeekBasedYear())
                    WeekNumberCell(weekNumber, Modifier.fillMaxHeight())
                }
                week.forEach { cell ->
                    TitlesDayCell(cell, locale, addLabel, measurer, onDayClick, onDayLongClick)
                }
            }
        }
    }
}

@Composable
private fun RowScope.TitlesDayCell(
    cell: DayCellData,
    locale: Locale,
    addLabel: String,
    measurer: TextMeasurer,
    onClick: (LocalDate) -> Unit,
    onLongClick: (LocalDate) -> Unit,
) {
    // The date, then every title — those behind "+2" included. A screen reader speaks a node's
    // description INSTEAD of its text, so a description holding the date alone silenced the titles
    // (external review, 2026-10-05).
    val titles = cell.events.map { displayTitle(it.title, it.birthdayAge) }
    val description = (listOf(dayDescription(cell, locale, withCount = false)) + titles).joinToString(", ")
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .border(Dp.Hairline, MaterialTheme.colorScheme.outlineVariant)
            .selectedDayOutline(cell.isSelected, MaterialTheme.colorScheme.primary, SELECTED_SHAPE)
            .dayGestures(
                onClick = { onClick(cell.date) },
                onLongClick = { onLongClick(cell.date) },
                longClickLabel = addLabel,
            )
            .semantics {
                contentDescription = description
                selected = cell.isSelected
            }
            .padding(horizontal = CELL_PADDING, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DayNumber(cell, size = 22.dp, style = MaterialTheme.typography.labelMedium)
        CellTitles(
            events = cell.events,
            date = cell.date,
            locale = locale,
            faded = !cell.isInMonth,
            measurer = measurer,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 2.dp)
                // A rounding the plan did not foresee must cut the cell's own last line, not spill onto
                // the week below.
                .clipToBounds(),
        )
    }
}

/**
 * The titles of one day, sharing the cell's lines by [CellLines]. Each title is measured at the width
 * it will be drawn at, with the style it will be drawn in, so the plan and the drawing cannot disagree
 * on where a title wraps.
 */
@Composable
private fun CellTitles(
    events: List<OccurrenceData>,
    date: LocalDate,
    locale: Locale,
    faded: Boolean,
    measurer: TextMeasurer,
    modifier: Modifier,
) {
    if (events.isEmpty()) return
    val style = cellTitleStyle()
    val zone = LocalDeviceZone.current
    val timeFormatter = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
    val dayStart = remember(date, zone) { date.atStartOfDay(zone).toInstant().toEpochMilli() }
    BoxWithConstraints(modifier = modifier) {
        // The start time earns its place only where the cell is wide enough to keep a useful part of
        // the title after it: a phone held sideways, a tablet.
        val withTime = maxWidth >= TIME_MIN_CELL_WIDTH
        val labels = events.map { event ->
            val title = displayTitle(event.title, event.birthdayAge)
            if (withTime && !event.allDay && event.startUtcMillis >= dayStart) {
                Instant.ofEpochMilli(event.startUtcMillis).atZone(zone).format(timeFormatter) + " " + title
            } else {
                title
            }
        }

        val density = LocalDensity.current
        // In pixels, rounded padding by padding exactly as the layout rounds them: a width one pixel
        // off is enough to wrap a title at another word than the one it is drawn at.
        val textWidthPx = with(density) {
            constraints.maxWidth - 2 * CHIP_MARGIN.roundToPx() - 2 * CHIP_TEXT_PADDING.roundToPx()
        }.coerceAtLeast(1)
        val lineHeightPx = with(density) { style.lineHeight.toPx() }
        val gapPx = with(density) { CHIP_GAP.toPx() }
        val lines = ((constraints.maxHeight + gapPx) / (lineHeightPx + gapPx)).toInt()
        // Measured only when the cell has lines to spare: with as many events as lines or more, every
        // title shown gets one line whatever its length (CellLines.plan), and measuring them decided
        // nothing. On a busy agenda that was every title of every day — about 670 measurements for one
        // page of 16 events a day, a 100–150 ms stall on the S9 each time a month settled.
        val needs = remember(labels, textWidthPx, style, density, lines) {
            if (labels.size >= lines) {
                List(labels.size) { 1 }
            } else {
                labels.map { label ->
                    measurer.measure(label, style, constraints = Constraints(maxWidth = textWidthPx)).lineCount
                }
            }
        }
        val plan = CellLines.plan(needs, lines)
        // On a single shared line, "+2" leaves the title what is left of the width. Below three
        // characters it showed only "…" (seen at 200 % text): the dots say more than that.
        val inlineTitleFits = !plan.moreInline || remember(plan.hidden, textWidthPx, style, density) {
            val more = measurer.measure("+${plan.hidden}", style, maxLines = 1).size.width
            val threeChars = measurer.measure(MIN_INLINE_TITLE, style, maxLines = 1).size.width
            textWidthPx - more - with(density) { CHIP_TEXT_PADDING.roundToPx() } >= threeChars
        }

        when {
            // Not even one line fits (a very large font), or no useful part of a title would: the dots.
            plan.linesPerEvent.isEmpty() || !inlineTitleFits -> EventDots(events, Modifier.align(Alignment.TopCenter))
            plan.moreInline -> Row(verticalAlignment = Alignment.CenterVertically) {
                TitleChip(labels[0], events[0], maxLines = 1, style, faded, Modifier.weight(1f))
                MoreEvents(plan.hidden, style)
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
                plan.linesPerEvent.forEachIndexed { index, maxLines ->
                    TitleChip(labels[index], events[index], maxLines, style, faded, Modifier.fillMaxWidth())
                }
                if (plan.hidden > 0) MoreEvents(plan.hidden, style)
            }
        }
    }
}

/** "+2" on the last line — said in words to a screen reader, which would otherwise read "plus two". */
@Composable
private fun MoreEvents(hidden: Int, style: TextStyle) {
    val more = pluralStringResource(R.plurals.month_more_events, hidden, hidden)
    Text(
        text = "+$hidden",
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            // Lined up with the titles' text.
            .padding(start = CHIP_MARGIN + CHIP_TEXT_PADDING)
            .semantics { contentDescription = more },
    )
}

/**
 * One title on a solid block of its event's colour — the colour of its calendar, or the one chosen in
 * the editor, so calendars stay apart. The text is white on dark colours and near-black on light ones,
 * whichever reads better: white on the palette's yellow, coral or sky blue falls to 1.7–3.4:1.
 * A day of another month gets the pale blue of the bottom bar's active item (the theme's secondary
 * container) whatever its event's colour, with the secondary text colour: it reads as belonging to
 * another month, and the palette stays out of the way.
 *
 * Tried and dropped on the device: a pale tint behind dark text, a stronger one behind all-day events
 * (read as dark blocks), and a colour bar beside the text. All-day events come first in the cell.
 */
@Composable
private fun TitleChip(
    label: String,
    event: OccurrenceData,
    maxLines: Int,
    style: TextStyle,
    faded: Boolean,
    modifier: Modifier,
) {
    val block = if (faded) MaterialTheme.colorScheme.secondaryContainer else Color(event.colorArgb)
    val text = when {
        faded -> MaterialTheme.colorScheme.onSurfaceVariant
        block.luminance() < WHITE_TEXT_BELOW_LUMINANCE -> Color.White
        else -> DARK_TEXT
    }
    Text(
        text = label,
        style = style,
        color = text,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(horizontal = CHIP_MARGIN)
            .clip(CHIP_SHAPE)
            .background(block)
            .padding(horizontal = CHIP_TEXT_PADDING),
    )
}

/**
 * Small, tight and hyphenated: a cell is seven or eight characters wide, so a word longer than that is
 * cut — with a hyphen where the language allows one ("Anniver-saire"), rather than at whatever letter
 * meets the edge ("Annivers" / "aire"). The measure in [CellTitles] uses this same style, hyphens
 * included, so it wraps where the drawing does.
 */
@Composable
private fun cellTitleStyle(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(
        fontSize = 11.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.sp,
        hyphens = Hyphens.Auto,
    )

private val CELL_PADDING = 1.dp
private val SELECTED_SHAPE = RoundedCornerShape(3.dp)
private val CHIP_GAP = 2.dp
private val CHIP_SHAPE = RoundedCornerShape(3.dp)
private val CHIP_MARGIN = 1.dp
private val CHIP_TEXT_PADDING = 3.dp

/**
 * Below this luminance a block takes white text, above it dark text: the crossing point where both
 * give the same contrast (about 4.6:1), so the chosen one is never below that.
 */
private const val WHITE_TEXT_BELOW_LUMINANCE = 0.179f
private val DARK_TEXT = Color(0xFF1B1B1F)
private val TIME_MIN_CELL_WIDTH = 88.dp

/** The shortest piece of a title worth showing beside "+2": three characters, ellipsis included. */
private const val MIN_INLINE_TITLE = "Ab…"
