package com.filestech.agenda_tech.ui.screens.timeline

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.ui.CalendarScaffold
import com.filestech.agenda_tech.ui.navigation.CalendarView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun DayScreen(
    onSelectView: (CalendarView) -> Unit,
    onAddEvent: (LocalDate) -> Unit,
    onOccurrenceClick: (Long) -> Unit,
    viewModel: DayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

    CalendarScaffold(
        currentView = CalendarView.DAY,
        onSelectView = onSelectView,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(dayHeaderLabel(state.day.date, locale)) },
                navigationIcon = {
                    IconButton(onClick = viewModel::onPreviousDay) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.day_previous))
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::onToday) { Text(stringResource(R.string.month_today)) }
                    IconButton(onClick = viewModel::onNextDay) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.day_next))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onAddEvent(state.day.date) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.month_add_event))
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            if (state.day.allDayItems.isNotEmpty()) {
                AllDayStrip(state.day.allDayItems, onOccurrenceClick)
                HorizontalDivider()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                HourGutter()
                EventsColumn(
                    positioned = state.day.positioned,
                    nowMinute = state.day.nowMinute,
                    onItemClick = onOccurrenceClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun dayHeaderLabel(date: LocalDate, locale: Locale): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
