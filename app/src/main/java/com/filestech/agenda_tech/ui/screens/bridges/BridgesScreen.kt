package com.filestech.agenda_tech.ui.screens.bridges

import android.icu.text.ListFormatter
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.domain.holiday.Bridge
import com.filestech.agenda_tech.domain.holiday.HolidayCountry
import com.filestech.agenda_tech.ui.util.displayName
import com.filestech.agenda_tech.ui.util.holidayColor
import com.filestech.agenda_tech.ui.util.holidayName
import com.filestech.agenda_tech.ui.util.lightLineColor
import com.filestech.agenda_tech.ui.util.rememberAppLocale
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The bridge days of the country whose holidays the calendar shows, this year's still ahead or the next
 * year's: for each, the days to take off and the rest they give. Read only — nothing here is an event.
 */
@Composable
fun BridgesScreen(
    onBack: () -> Unit,
    viewModel: BridgesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bridges_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.editor_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        val country = state.country
        when {
            !state.loaded -> Unit
            // Reached only if the country is taken away while this screen is open: the settings show the
            // way here only when one is chosen.
            country == null -> Text(
                text = stringResource(R.string.bridges_no_country),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(16.dp),
            )
            else -> BridgesList(
                state = state,
                country = country,
                onSelectYear = viewModel::selectYear,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun BridgesList(
    state: BridgesUiState,
    country: HolidayCountry,
    onSelectYear: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = rememberAppLocale()
    // "Fri 7 May", "ven. 7 mai", "Fr., 7. Mai": the order and the punctuation of the app's language.
    val dayFormat = remember(locale) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale)
    }
    // "A and B" in the app's language, for two days to take or two holidays.
    val listFormat = remember(locale) { ListFormatter.getInstance(locale) }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item(key = "header") {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = country.displayName(locale), style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    state.years.forEachIndexed { index, year ->
                        SegmentedButton(
                            selected = year == state.year,
                            onClick = { onSelectYear(year) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = state.years.size),
                        ) {
                            Text(year.toString())
                        }
                    }
                }
            }
        }
        if (state.bridges.isEmpty()) {
            item(key = "none") {
                Text(
                    text = stringResource(R.string.bridges_none, state.year),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        itemsIndexed(state.bridges, key = { _, bridge -> bridge.daysToTake.first().toEpochDay() }) { index, bridge ->
            if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = lightLineColor())
            BridgeRow(bridge = bridge, dayFormat = dayFormat, listFormat = listFormat)
        }
        item(key = "note") {
            HorizontalDivider(color = lightLineColor())
            Text(
                text = stringResource(R.string.bridges_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/** One bridge: the holidays it is made of, the days to take off, then the rest it gives. */
@Composable
private fun BridgeRow(bridge: Bridge, dayFormat: DateTimeFormatter, listFormat: ListFormatter) {
    val resources = LocalResources.current
    // A substitute day and the holiday it stands in for can both fall in one stretch: one name each.
    val names = bridge.holidays.map { holidayName(resources, it) }.distinct()
    val daysToTake = listFormat.format(bridge.daysToTake.map { dayFormat.unbroken(it) })
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Flag,
            contentDescription = null,
            tint = holidayColor(),
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = listFormat.format(names), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.bridges_take, daysToTake),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.bridges_rest,
                    bridge.restDays,
                    bridge.restDays,
                    dayFormat.unbroken(bridge.restStart),
                    dayFormat.unbroken(bridge.restEnd),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A date that wraps as one block: "dim. 9" at the end of a line and "mai" on the next read as two things
 * (seen on the S9). Its spaces become no-break spaces; the text around it still wraps.
 */
private fun DateTimeFormatter.unbroken(date: LocalDate): String = format(date).replace(' ', NO_BREAK_SPACE)

private val NO_BREAK_SPACE = Char(0x00A0)
