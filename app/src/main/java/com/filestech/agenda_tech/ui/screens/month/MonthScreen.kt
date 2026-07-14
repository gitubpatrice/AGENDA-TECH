package com.filestech.agenda_tech.ui.screens.month

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.agenda_tech.R
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun MonthScreen(
    onAddEvent: (LocalDate) -> Unit,
    onOccurrenceClick: (Long) -> Unit,
    viewModel: MonthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MonthScreenContent(
        state = state,
        onPreviousMonth = viewModel::onPreviousMonth,
        onNextMonth = viewModel::onNextMonth,
        onToday = viewModel::onToday,
        onSelectDate = viewModel::onSelectDate,
        onAddEvent = onAddEvent,
        onOccurrenceClick = onOccurrenceClick,
    )
}

@Composable
private fun MonthScreenContent(
    state: MonthUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onAddEvent: (LocalDate) -> Unit,
    onOccurrenceClick: (Long) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = monthLabel(state.yearMonth, locale)) },
                navigationIcon = {
                    IconButton(onClick = onPreviousMonth) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.month_previous),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onToday) { Text(stringResource(R.string.month_today)) }
                    IconButton(onClick = onNextMonth) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.month_next),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onAddEvent(state.selectedDate) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.month_add_event))
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            WeekdayHeader(state.firstDayOfWeek, locale)
            state.weeks.forEach { week ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    week.forEach { cell -> DayCell(cell = cell, onClick = onSelectDate) }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = dayLabel(state.selectedDate, locale),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            SelectedDayOccurrences(
                occurrences = state.selectedDayOccurrences,
                locale = locale,
                onOccurrenceClick = onOccurrenceClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

@Composable
private fun WeekdayHeader(firstDayOfWeek: DayOfWeek, locale: Locale) {
    Row(modifier = Modifier.fillMaxWidth()) {
        MonthGrid.weekdayHeaders(firstDayOfWeek).forEach { dow ->
            Text(
                text = dow.getDisplayName(TextStyle.SHORT, locale),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun RowScope.DayCell(cell: DayCellData, onClick: (LocalDate) -> Unit) {
    val numberColor = when {
        cell.isToday -> MaterialTheme.colorScheme.onPrimary
        !cell.isInMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .height(56.dp)
            .clip(MaterialTheme.shapes.small)
            .then(
                if (cell.isSelected) {
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                } else {
                    Modifier
                },
            )
            .clickable { onClick(cell.date) }
            .padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .then(
                    if (cell.isToday) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier,
                ),
        ) {
            Text(
                text = cell.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (cell.isToday) FontWeight.Bold else FontWeight.Normal,
                color = numberColor,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            cell.eventColors.forEach { argb ->
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(Color(argb)),
                )
            }
        }
    }
}

@Composable
private fun SelectedDayOccurrences(
    occurrences: List<OccurrenceData>,
    locale: Locale,
    onOccurrenceClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (occurrences.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.month_no_events),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val zone = remember { ZoneId.systemDefault() }
    LazyColumn(modifier = modifier) {
        items(occurrences, key = { it.eventId to it.startUtcMillis }) { occurrence ->
            OccurrenceRow(occurrence, zone, locale, onOccurrenceClick)
        }
    }
}

@Composable
private fun OccurrenceRow(
    occurrence: OccurrenceData,
    zone: ZoneId,
    locale: Locale,
    onOccurrenceClick: (Long) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOccurrenceClick(occurrence.eventId) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color(occurrence.colorArgb)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = occurrence.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = timeLabel(occurrence, zone, locale),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- formatting helpers (UI-side, locale-aware) -----------------------------

private fun monthLabel(yearMonth: YearMonth, locale: Locale): String {
    val month = yearMonth.month.getDisplayName(TextStyle.FULL, locale)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    return "$month ${yearMonth.year}"
}

private fun dayLabel(date: LocalDate, locale: Locale): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

@Composable
private fun timeLabel(occurrence: OccurrenceData, zone: ZoneId, locale: Locale): String {
    if (occurrence.allDay) return stringResource(R.string.month_all_day)
    val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    val start = Instant.ofEpochMilli(occurrence.startUtcMillis).atZone(zone).format(formatter)
    val end = Instant.ofEpochMilli(occurrence.endUtcMillis).atZone(zone).format(formatter)
    return "$start – $end"
}
