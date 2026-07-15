package com.filestech.agenda_tech.ui.screens.agenda

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.ui.CalendarScaffold
import com.filestech.agenda_tech.ui.navigation.CalendarView
import com.filestech.agenda_tech.ui.screens.timeline.TimelineItem
import com.filestech.agenda_tech.ui.util.EventRow
import com.filestech.agenda_tech.ui.util.EventRowDetail
import com.filestech.agenda_tech.ui.util.rememberAppLocale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun AgendaScreen(
    onSelectView: (CalendarView) -> Unit,
    onAddEvent: (LocalDate) -> Unit,
    onOccurrenceClick: (Long, Long) -> Unit,
    viewModel: AgendaViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val locale = rememberAppLocale()
    val zone = ZoneId.systemDefault()
    val listState = rememberLazyListState()

    // The window spans a year of past events; open the list at today rather than a year ago.
    var scrolledToToday by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.days) {
        if (!scrolledToToday && state.days.isNotEmpty()) {
            listState.scrollToItem(state.days.todayFlatIndex(viewModel.startDate))
            scrolledToToday = true
        }
    }

    CalendarScaffold(
        currentView = CalendarView.AGENDA,
        onSelectView = onSelectView,
        topBar = {
            CenterAlignedTopAppBar(title = { Text(stringResource(R.string.view_agenda)) })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onAddEvent(viewModel.startDate) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.month_add_event))
            }
        },
    ) { innerPadding ->
        if (state.days.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.agenda_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@CalendarScaffold
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            state.days.forEach { day ->
                item(key = "header-${day.date}") {
                    DayHeader(day.date, locale)
                }
                items(day.items, key = { it.eventId to it.startUtcMillis }) { item ->
                    AgendaRow(item, zone, locale, onOccurrenceClick)
                }
            }
        }
    }
}

/**
 * Flat LazyColumn index of the first day on/after [today] (each day = 1 header + N rows). Falls back
 * to the end of the list when every event is in the past.
 */
private fun List<AgendaDay>.todayFlatIndex(today: LocalDate): Int {
    var index = 0
    for (day in this) {
        if (!day.date.isBefore(today)) return index
        index += 1 + day.items.size
    }
    return (index - 1).coerceAtLeast(0)
}

@Composable
private fun DayHeader(date: LocalDate, locale: Locale) {
    Text(
        text = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun AgendaRow(
    item: TimelineItem,
    zone: ZoneId,
    locale: Locale,
    onOccurrenceClick: (Long, Long) -> Unit,
) {
    EventRow(
        title = item.title,
        colorArgb = item.colorArgb,
        onClick = { onOccurrenceClick(item.eventId, item.startUtcMillis) },
    ) {
        EventRowDetail(timeLabel(item, zone, locale))
    }
}

@Composable
private fun timeLabel(item: TimelineItem, zone: ZoneId, locale: Locale): String {
    if (item.allDay) return stringResource(R.string.month_all_day)
    val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    val start = Instant.ofEpochMilli(item.startUtcMillis).atZone(zone).format(formatter)
    val end = Instant.ofEpochMilli(item.endUtcMillis).atZone(zone).format(formatter)
    return "$start – $end"
}
