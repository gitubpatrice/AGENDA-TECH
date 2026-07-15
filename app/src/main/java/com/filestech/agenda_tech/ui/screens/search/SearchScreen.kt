package com.filestech.agenda_tech.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.domain.search.EventSearchHit
import com.filestech.agenda_tech.ui.util.rememberAppLocale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Free-text search across the whole agenda.
 *
 * Results are split "upcoming" / "past" because those map to the two questions people actually
 * search an agenda with — *when is* my dentist, and *when was* it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOccurrenceClick: (eventId: Long, occurrenceStartUtcMillis: Long) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val hits by viewModel.hits.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // The screen exists only to be typed into; making the user tap the field first is a wasted step.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.editor_back))
                    }
                },
                title = {
                    TextField(
                        value = query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = { Text(stringResource(R.string.search_field_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        // Results are already live; Search just dismisses the keyboard to hand the
                        // whole screen back to the list.
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = viewModel::clearQuery) {
                                    Icon(Icons.Filled.Close, stringResource(R.string.search_clear))
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                },
            )
        },
    ) { padding ->
        // imePadding: Scaffold's default window insets cover the system bars but NOT the keyboard,
        // which is up the whole time on this screen — without it the centred hint is cut in half and
        // the last results sit under the keys.
        Box(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            when {
                query.isBlank() -> CenteredHint(stringResource(R.string.search_prompt))
                hits.isEmpty() -> CenteredHint(
                    text = stringResource(R.string.search_no_results, query.trim()),
                    detail = stringResource(R.string.search_no_results_hint),
                )
                else -> Results(hits, onOccurrenceClick)
            }
        }
    }
}

@Composable
private fun Results(
    hits: List<EventSearchHit>,
    onOccurrenceClick: (Long, Long) -> Unit,
) {
    val locale = rememberAppLocale()
    val zone = ZoneId.systemDefault()
    // The use case already orders upcoming-then-past; partitioning only splits, never reorders.
    val upcoming = hits.filter { it.isUpcoming }
    val past = hits.filterNot { it.isUpcoming }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (upcoming.isNotEmpty()) {
            item(key = "header-upcoming") { SectionHeader(stringResource(R.string.search_section_upcoming)) }
            items(upcoming, key = { "u-${it.event.id}" }) { hit ->
                SearchRow(hit, zone, locale, onOccurrenceClick)
            }
        }
        if (past.isNotEmpty()) {
            item(key = "header-past") { SectionHeader(stringResource(R.string.search_section_past)) }
            items(past, key = { "p-${it.event.id}" }) { hit ->
                SearchRow(hit, zone, locale, onOccurrenceClick)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SearchRow(
    hit: EventSearchHit,
    zone: ZoneId,
    locale: Locale,
    onOccurrenceClick: (Long, Long) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOccurrenceClick(hit.event.id, hit.occurrenceStartUtcMillis) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color((hit.event.colorOverride ?: hit.calendar.color).argb)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = hit.event.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = dateLabel(hit, zone, locale),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = calendarLabel(hit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Full date — search spans years, so a bare weekday would be ambiguous. All-day events show no clock
 * time; a recurring hit is dated by the occurrence the use case picked, not by the series' base.
 */
@Composable
private fun dateLabel(hit: EventSearchHit, zone: ZoneId, locale: Locale): String {
    val start = Instant.ofEpochMilli(hit.occurrenceStartUtcMillis).atZone(zone)
    val date = start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    if (hit.event.allDay) return "$date · ${stringResource(R.string.month_all_day)}"
    val time = start.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    return "$date · $time"
}

/**
 * The calendar the hit lives in — and, when that calendar is hidden, says so. Search deliberately
 * spans hidden calendars, so without this the user would find a result they cannot see in any view
 * and have no way to explain why.
 */
@Composable
private fun calendarLabel(hit: EventSearchHit): String =
    if (hit.calendar.isVisible) {
        hit.calendar.name
    } else {
        "${hit.calendar.name} · ${stringResource(R.string.search_hidden_calendar)}"
    }

@Composable
private fun CenteredHint(text: String, detail: String? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
