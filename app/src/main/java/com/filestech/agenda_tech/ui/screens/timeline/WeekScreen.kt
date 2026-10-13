package com.filestech.agenda_tech.ui.screens.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.domain.birthday.displayTitle
import com.filestech.agenda_tech.ui.CalendarScaffold
import com.filestech.agenda_tech.ui.navigation.CalendarView
import com.filestech.agenda_tech.ui.util.AddFab
import com.filestech.agenda_tech.ui.util.HolidayChip
import com.filestech.agenda_tech.ui.util.holidayColor
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
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            WeekDayHeader(state.days, locale)
            WeekAllDayRow(state.days, onOccurrenceClick)
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
                Text(
                    text = day.date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
                    // Today in blue; a public holiday in red, as in the month.
                    color = when {
                        day.isToday -> MaterialTheme.colorScheme.primary
                        day.holidays.isNotEmpty() -> holidayColor()
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * What a week day holds for the whole day — its public holidays, then its all-day events — in its own
 * column under its date. The all-day events were missing from the week altogether: the day's strip was
 * never drawn there (seen on the S9, 2026-10-13). Absent when no day of the week has any.
 */
@Composable
private fun WeekAllDayRow(days: List<DayTimelineData>, onItemClick: (Long, Long) -> Unit) {
    if (days.none { it.allDayItems.isNotEmpty() || it.holidays.isNotEmpty() }) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
    ) {
        Spacer(Modifier.width(HOUR_GUTTER_WIDTH))
        days.forEach { day ->
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
                day.allDayItems.take(MAX_ALL_DAY_PER_DAY).forEach { item -> WeekAllDayChip(item, onItemClick) }
                val hidden = day.allDayItems.size - MAX_ALL_DAY_PER_DAY
                if (hidden > 0) {
                    Text(
                        text = "+$hidden",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 3.dp),
                    )
                }
            }
        }
    }
}

/** One all-day event in a week column: the Day view's strip, narrowed — its colour, white text, a tap opens it. */
@Composable
private fun WeekAllDayChip(item: TimelineItem, onItemClick: (Long, Long) -> Unit) {
    Text(
        text = displayTitle(item.title, item.birthdayAge),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraSmall)
            .background(Color(item.colorArgb))
            .clickable { onItemClick(item.eventId, item.occurrenceStartUtcMillis) }
            .padding(horizontal = 3.dp, vertical = 1.dp),
    )
}

/** Past this many, a week column says how many more: a holiday week would otherwise push the hours away. */
private const val MAX_ALL_DAY_PER_DAY = 3

private fun weekLabel(weekStart: LocalDate, locale: Locale): String {
    val end = weekStart.plusDays(6)
    val startDay = weekStart.dayOfMonth
    val endDay = end.dayOfMonth
    val month = end.month.getDisplayName(TextStyle.SHORT, locale)
    return "$startDay – $endDay $month ${end.year}"
}
