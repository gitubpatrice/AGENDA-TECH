package com.filestech.agenda_tech.ui.screens.agenda

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.ui.CalendarScaffold
import com.filestech.agenda_tech.ui.navigation.CalendarView
import com.filestech.agenda_tech.ui.screens.timeline.TimelineItem
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
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val zone = ZoneId.systemDefault()

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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOccurrenceClick(item.eventId, item.startUtcMillis) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color(item.colorArgb)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = timeLabel(item, zone, locale),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
