package com.filestech.agenda_tech.ui.screens.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.domain.birthday.displayTitle
import com.filestech.agenda_tech.ui.CalendarScaffold
import com.filestech.agenda_tech.ui.navigation.CalendarView
import com.filestech.agenda_tech.ui.util.AddFab
import com.filestech.agenda_tech.ui.util.HolidayChip
import com.filestech.agenda_tech.ui.util.HolidayDayShape
import com.filestech.agenda_tech.ui.util.MoreEventsLabel
import com.filestech.agenda_tech.ui.util.holidayColor
import com.filestech.agenda_tech.ui.util.onHolidayColor
import com.filestech.agenda_tech.ui.util.rememberAppLocale
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun WeekScreen(
    onSelectView: (CalendarView) -> Unit,
    onAddEvent: (LocalDate) -> Unit,
    onOccurrenceClick: (Long, Long) -> Unit,
    viewModel: WeekViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val locale = rememberAppLocale()
    // The first day of the week follows the language the screen is drawn in (see WeekViewModel.appLocale).
    LaunchedEffect(locale) { viewModel.onLocaleChange(locale) }
    val addTargetDate = state.days.firstOrNull { it.isToday }?.date ?: state.weekStart
    // Folded at first, so a busy week keeps its hours in view; kept from one week to the next.
    var allDayExpanded by rememberSaveable { mutableStateOf(false) }

    CalendarScaffold(
        currentView = CalendarView.WEEK,
        onSelectView = onSelectView,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(weekLabel(state.weekStart, locale)) },
                navigationIcon = {
                    IconButton(onClick = viewModel::onPreviousWeek) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.week_previous))
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::onToday) { Text(stringResource(R.string.month_today)) }
                    IconButton(onClick = viewModel::onNextWeek) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.week_next))
                    }
                },
            )
        },
        floatingActionButton = {
            AddFab(
                onClick = { onAddEvent(addTargetDate) },
                contentDescription = stringResource(R.string.month_add_event),
            )
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            // Unfolded, a day of many all-day events scrolls within this share of the screen, so the hours
            // stay in view (pre-release audit, 2026-10-22).
            val allDayCap = maxHeight * ALL_DAY_MAX_SHARE
            Column(modifier = Modifier.fillMaxSize()) {
                WeekDayHeader(state.days, locale)
                WeekAllDayRow(
                    days = state.days,
                    expanded = allDayExpanded,
                    onExpandedChange = { allDayExpanded = it },
                    onItemClick = onOccurrenceClick,
                    heightCap = allDayCap,
                )
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    HourGutter()
                    state.days.forEach { day ->
                        VerticalDivider()
                        EventsColumn(
                            positioned = day.positioned,
                            nowMinute = day.nowMinute,
                            onItemClick = onOccurrenceClick,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekDayHeader(days: List<DayTimelineData>, locale: Locale) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(HOUR_GUTTER_WIDTH))
        days.forEach { day ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                // Today in blue; a public holiday in its red square, as in the month — today first.
                val holiday = day.holidays.isNotEmpty() && !day.isToday
                Text(
                    text = day.date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        day.isToday -> MaterialTheme.colorScheme.primary
                        holiday -> onHolidayColor()
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    textAlign = TextAlign.Center,
                    modifier = if (holiday) {
                        Modifier
                            .background(holidayColor(), HolidayDayShape)
                            .padding(horizontal = 4.dp)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

/**
 * What a week day holds for the whole day — its public holidays, then its all-day events — in its own
 * column under its date. The all-day events were missing from the week altogether: the day's strip was
 * never drawn there (seen on the S9, 2026-10-13). Absent when no day of the week has any.
 *
 * Past [MAX_ALL_DAY_PER_DAY], a column says how many more. That "+N" did nothing (2026-10-13): a tap on
 * it now unfolds the whole row, as an arrow in the hours' gutter does — the arrow, always at the top
 * of the row, folds it back. Unfolded, the days scroll within [heightCap]; the arrow stays outside.
 */
@Composable
internal fun WeekAllDayRow(
    days: List<DayTimelineData>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onItemClick: (Long, Long) -> Unit,
    heightCap: Dp,
) {
    if (days.none { it.allDayItems.isNotEmpty() || it.holidays.isNotEmpty() }) return
    val foldable = days.any { it.allDayItems.size > MAX_ALL_DAY_PER_DAY }
    val expandLabel = stringResource(R.string.week_all_day_expand)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
    ) {
        Box(modifier = Modifier.width(HOUR_GUTTER_WIDTH), contentAlignment = Alignment.TopCenter) {
            if (foldable) {
                IconButton(onClick = { onExpandedChange(!expanded) }) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) stringResource(R.string.week_all_day_collapse) else expandLabel,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(max = heightCap)
                .verticalScroll(rememberScrollState()),
        ) {
            days.forEach { day -> WeekAllDayColumn(day, expanded, expandLabel, onExpandedChange, onItemClick) }
        }
    }
}

/** One day's column of the all-day row: its holidays, its all-day events, and the "+N" past three. */
@Composable
private fun RowScope.WeekAllDayColumn(
    day: DayTimelineData,
    expanded: Boolean,
    expandLabel: String,
    onExpandedChange: (Boolean) -> Unit,
    onItemClick: (Long, Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .padding(horizontal = 1.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        day.holidays.forEach { holiday ->
            // Hyphenated where the language allows, as in the month: "Armisti / ce" read badly.
            HolidayChip(holiday, MaterialTheme.typography.labelSmall.copy(hyphens = Hyphens.Auto), maxLines = 2)
        }
        val shown = if (expanded) day.allDayItems else day.allDayItems.take(MAX_ALL_DAY_PER_DAY)
        shown.forEach { item -> WeekAllDayChip(item, onItemClick) }
        val hidden = day.allDayItems.size - shown.size
        if (hidden > 0) {
            // Said in words to a screen reader, as in the month (pre-release audit, 2026-10-13); the
            // column's width and 24 dp to aim at, as the strips above it.
            MoreEventsLabel(
                hidden = hidden,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 24.dp)
                    .clickable(role = Role.Button, onClickLabel = expandLabel) { onExpandedChange(true) }
                    .wrapContentHeight()
                    .padding(horizontal = 3.dp),
            )
        }
    }
}

/** One all-day event in a week column: the Day view's strip, narrowed — its colour, white text, a tap opens it. */
@Composable
private fun WeekAllDayChip(item: TimelineItem, onItemClick: (Long, Long) -> Unit) {
    // At least 24 dp tall, the smallest target WCAG 2.5.8 accepts (pre-release audit, 2026-10-13): one
    // line of small text made a strip about 15 dp high to aim a finger at.
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 24.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(Color(item.colorArgb))
            .clickable(role = Role.Button) { onItemClick(item.eventId, item.occurrenceStartUtcMillis) }
            .padding(horizontal = 3.dp),
    ) {
        Text(
            text = displayTitle(item.title, item.birthdayAge),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Past this many, a week column says how many more: a holiday week would otherwise push the hours away. */
private const val MAX_ALL_DAY_PER_DAY = 3

/** The share of the screen the unfolded all-day row may take before it scrolls. */
private const val ALL_DAY_MAX_SHARE = 0.4f

private fun weekLabel(weekStart: LocalDate, locale: Locale): String {
    val end = weekStart.plusDays(6)
    val startDay = weekStart.dayOfMonth
    val endDay = end.dayOfMonth
    val month = end.month.getDisplayName(TextStyle.SHORT, locale)
    return "$startDay – $endDay $month ${end.year}"
}
