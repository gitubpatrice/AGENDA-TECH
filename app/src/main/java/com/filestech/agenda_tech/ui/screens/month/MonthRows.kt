package com.filestech.agenda_tech.ui.screens.month

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.domain.birthday.displayTitle
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The month as one full-width row per day: every title whole, and the free days kept in place — the
 * difference with the Agenda tab, which lists only the days that hold something and does not stop at
 * the end of the month.
 *
 * A tap on a day selects it (the "+" then adds there), a tap on an event opens it, a long press on a
 * day creates an event on it — the same gestures as in the grids.
 */
@Composable
internal fun MonthRows(
    weeks: List<List<DayCellData>>,
    firstDayOfWeek: DayOfWeek,
    showWeekNumbers: Boolean,
    scrollRequest: Int,
    scrollTarget: LocalDate,
    locale: Locale,
    onDayClick: (LocalDate) -> Unit,
    onDayLongClick: (LocalDate) -> Unit,
    onOccurrenceClick: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val days = remember(weeks) { weeks.flatten().filter { it.isInMonth } }
    // Opens on the selected day — else today — with the day before still in view; the weeks already
    // behind are one flick away.
    val anchor = days.indexOfFirst { it.isSelected }.takeIf { it >= 0 }
        ?: days.indexOfFirst { it.isToday }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (anchor - 1).coerceAtLeast(0))
    val zone = remember { ZoneId.systemDefault() }
    val timeFormatter = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
    val addLabel = stringResource(R.string.month_add_event)
    // The time column is as wide as the widest time of the format in use, so the titles line up: in a
    // twelve-hour locale "7:00 AM" is narrower than "12:00 PM", and the titles after them zigzagged.
    val timeStyle = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val timeWidth = remember(timeFormatter, timeStyle, density) {
        val widest = listOf(LocalTime.of(0, 59), LocalTime.of(12, 59)).maxOf { time ->
            measurer.measure(time.format(timeFormatter), timeStyle, maxLines = 1).size.width
        }
        with(density) { widest.toDp() }
    }
    // Same for the weekday names: the widest of the seven, measured in the language shown, rather than
    // a fixed width that cut "mar." to "ma" once the text was enlarged.
    val weekdayStyle = MaterialTheme.typography.labelMedium
    val weekdayWidth = remember(locale, weekdayStyle, density) {
        val widest = DayOfWeek.entries.maxOf { day ->
            measurer.measure(day.getDisplayName(TextStyle.SHORT, locale), weekdayStyle, maxLines = 1).size.width
        }
        with(density) { widest.toDp() } + WEEKDAY_GAP
    }

    // A day is brought into view when asked — "Today", the date picker — and only then. Following the
    // selection instead ran again on every return to the page, throwing away the position restored
    // after the editor, and "Today" did nothing when today was already selected, since nothing
    // changed (pre-release audit, 2026-10-05). The request carries its date, so it does not depend on
    // the order in which the new selection and the request reach the screen.
    var handledRequest by rememberSaveable { mutableIntStateOf(scrollRequest) }
    LaunchedEffect(scrollRequest) {
        if (scrollRequest == handledRequest) return@LaunchedEffect
        handledRequest = scrollRequest
        val index = days.indexOfFirst { it.date == scrollTarget }
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.animateScrollToItem((index - 1).coerceAtLeast(0))
        }
    }

    // The bottom padding lets the last day scroll clear of the "+" button floating over the list.
    LazyColumn(modifier = modifier, state = listState, contentPadding = PaddingValues(bottom = FAB_CLEARANCE)) {
        itemsIndexed(days, key = { _, cell -> cell.date.toEpochDay() }) { index, cell ->
            // A rule before each new week, and the number of the first week too — it begins before the
            // 1st, so no row of this month starts it (external review, 2026-10-05).
            val firstRow = index == 0
            val separator = if (firstRow) showWeekNumbers else cell.date.dayOfWeek == firstDayOfWeek
            if (separator) {
                WeekSeparator(
                    weekStart = cell.date.with(TemporalAdjusters.previousOrSame(firstDayOfWeek)),
                    showWeekNumbers = showWeekNumbers,
                    rule = !firstRow,
                )
            }
            DayRow(
                cell, locale, zone, timeFormatter, timeWidth, weekdayWidth, addLabel,
                onDayClick, onDayLongClick, onOccurrenceClick,
            )
        }
    }
}

/** A rule between weeks, so the eye finds a week as quickly as in the grid; [rule] false for the first. */
@Composable
private fun WeekSeparator(weekStart: LocalDate, showWeekNumbers: Boolean, rule: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showWeekNumbers) {
            // Read from the middle of the week, as the grids do: from its first day, a week starting on
            // Sunday 29 December 2024 was week 52 here and week 1 in the grid.
            Text(
                text = weekStart.plusDays(MID_WEEK_OFFSET).get(WeekFields.ISO.weekOfWeekBasedYear()).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        if (rule) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun DayRow(
    cell: DayCellData,
    locale: Locale,
    zone: ZoneId,
    timeFormatter: DateTimeFormatter,
    timeWidth: Dp,
    weekdayWidth: Dp,
    addLabel: String,
    onDayClick: (LocalDate) -> Unit,
    onDayLongClick: (LocalDate) -> Unit,
    onOccurrenceClick: (Long, Long) -> Unit,
) {
    val description = dayDescription(cell, locale, withCount = false)
    val dayStart = remember(cell.date, zone) { cell.date.atStartOfDay(zone).toInstant().toEpochMilli() }
    val dayEnd = remember(cell.date, zone) { cell.date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .selectedDayOutline(cell.isSelected, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
            .dayGestures(
                onClick = { onDayClick(cell.date) },
                onLongClick = { onDayLongClick(cell.date) },
                longClickLabel = addLabel,
            )
            .semantics {
                contentDescription = description
                selected = cell.isSelected
            }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = cell.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.width(weekdayWidth),
            )
            DayNumber(cell, size = 26.dp, style = MaterialTheme.typography.labelLarge)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            cell.events.forEach { event ->
                val time = rowTime(event, dayStart, dayEnd, zone, timeFormatter)
                RowEvent(event, time, timeWidth, onOccurrenceClick)
            }
        }
    }
}

@Composable
private fun RowEvent(event: OccurrenceData, time: String?, timeWidth: Dp, onOccurrenceClick: (Long, Long) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { onOccurrenceClick(event.eventId, event.startUtcMillis) }
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // As tall as a line of the title: a fixed 16 dp became a short tick next to enlarged text.
        val barHeight = with(LocalDensity.current) { maxOf(16.dp, MaterialTheme.typography.bodyMedium.fontSize.toDp()) }
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = barHeight)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(event.colorArgb)),
        )
        if (time != null) {
            Text(
                text = time,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                // A minimum, not a width: "→ 10:00", the end of an event that began the day before, is
                // longer and may push its own title along — one row out of line rather than a clipped time.
                modifier = Modifier
                    .padding(start = 6.dp)
                    .widthIn(min = timeWidth),
            )
        }
        // No line limit: this display exists to show every title whole.
        Text(
            text = displayTitle(event.title, event.birthdayAge),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(start = 6.dp),
        )
    }
}

/**
 * The time shown before a title: its start when it starts that day, "→ end" when it began earlier and
 * ends that day, nothing for an all-day event or a day it covers entirely — a start time borrowed from
 * a previous day would put the event at the wrong hour.
 */
private fun rowTime(
    event: OccurrenceData,
    dayStart: Long,
    dayEnd: Long,
    zone: ZoneId,
    formatter: DateTimeFormatter,
): String? {
    fun format(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).format(formatter)
    return when {
        event.allDay -> null
        event.startUtcMillis >= dayStart -> format(event.startUtcMillis)
        event.endUtcMillis < dayEnd -> "→ " + format(event.endUtcMillis)
        else -> null
    }
}

private val ROW_MIN_HEIGHT = 36.dp
private val FAB_CLEARANCE = 88.dp
private val WEEKDAY_GAP = 6.dp
private const val MID_WEEK_OFFSET = 3L
