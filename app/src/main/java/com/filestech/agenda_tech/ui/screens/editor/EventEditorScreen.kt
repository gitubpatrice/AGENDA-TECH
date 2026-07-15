package com.filestech.agenda_tech.ui.screens.editor

import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.location.GeoLink
import com.filestech.agenda_tech.domain.model.Weekday
import com.filestech.agenda_tech.ui.theme.BrandDanger
import timber.log.Timber
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

private enum class EditTarget { START, END, RECURRENCE_UNTIL }

@Composable
fun EventEditorScreen(
    onDone: () -> Unit,
    viewModel: EventEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // One-shot: pop back once the save/delete lands (never navigate during composition).
    androidx.compose.runtime.LaunchedEffect(state.isSaved, state.isDeleted) {
        if (state.isSaved || state.isDeleted) onDone()
    }

    state.scopePrompt?.let { prompt ->
        ScopeDialog(
            prompt = prompt,
            onSelect = viewModel::confirmScope,
            onDismiss = viewModel::dismissScope,
        )
    }

    EventEditorContent(
        state = state,
        onBack = onDone,
        onSave = viewModel::onSave,
        onDelete = viewModel::onDelete,
        onTitleChange = viewModel::onTitleChange,
        onAllDayChange = viewModel::onAllDayChange,
        onStartDateChange = viewModel::onStartDateChange,
        onStartTimeChange = viewModel::onStartTimeChange,
        onEndDateChange = viewModel::onEndDateChange,
        onEndTimeChange = viewModel::onEndTimeChange,
        onCalendarSelect = viewModel::onCalendarSelect,
        onColorChange = viewModel::onColorChange,
        onRecurrenceSelect = viewModel::onRecurrenceSelect,
        onRecurrenceIntervalChange = viewModel::onRecurrenceIntervalChange,
        onToggleRecurrenceWeekday = viewModel::onToggleRecurrenceWeekday,
        onRecurrenceEndChange = viewModel::onRecurrenceEndChange,
        onRecurrenceCountChange = viewModel::onRecurrenceCountChange,
        onRecurrenceUntilDateChange = viewModel::onRecurrenceUntilDateChange,
        onAddReminder = viewModel::onAddReminder,
        onRemoveReminder = viewModel::onRemoveReminder,
        onDescriptionChange = viewModel::onDescriptionChange,
        onLocationChange = viewModel::onLocationChange,
        onAddressChange = viewModel::onAddressChange,
        onPostalCodeChange = viewModel::onPostalCodeChange,
        onCityChange = viewModel::onCityChange,
        onGpsCoordinatesChange = viewModel::onGpsCoordinatesChange,
    )
}

@Composable
private fun ScopeDialog(
    prompt: ScopePrompt,
    onSelect: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (prompt == ScopePrompt.DELETE) R.string.scope_delete_title else R.string.scope_edit_title,
                ),
            )
        },
        text = {
            Column {
                TextButton(onClick = { onSelect(false) }) {
                    Text(stringResource(R.string.scope_this_occurrence))
                }
                TextButton(onClick = { onSelect(true) }) {
                    Text(stringResource(R.string.scope_whole_series))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun EventEditorContent(
    state: EventEditorUiState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onTitleChange: (String) -> Unit,
    onAllDayChange: (Boolean) -> Unit,
    onStartDateChange: (LocalDate) -> Unit,
    onStartTimeChange: (Int, Int) -> Unit,
    onEndDateChange: (LocalDate) -> Unit,
    onEndTimeChange: (Int, Int) -> Unit,
    onCalendarSelect: (Long) -> Unit,
    onColorChange: (CalendarColor?) -> Unit,
    onRecurrenceSelect: (RecurrenceFreq?) -> Unit,
    onRecurrenceIntervalChange: (Int) -> Unit,
    onToggleRecurrenceWeekday: (Weekday) -> Unit,
    onRecurrenceEndChange: (RecurrenceEnd) -> Unit,
    onRecurrenceCountChange: (Int) -> Unit,
    onRecurrenceUntilDateChange: (LocalDate) -> Unit,
    onAddReminder: (Int) -> Unit,
    onRemoveReminder: (Int) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onLocationChange: (String) -> Unit,
    onAddressChange: (String) -> Unit,
    onPostalCodeChange: (String) -> Unit,
    onCityChange: (String) -> Unit,
    onGpsCoordinatesChange: (String) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    var datePickerTarget by remember { mutableStateOf<EditTarget?>(null) }
    var timePickerTarget by remember { mutableStateOf<EditTarget?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.event_delete_confirm_title)) },
            text = { Text(stringResource(R.string.event_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.action_delete), color = BrandDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isEditing) R.string.editor_edit_title else R.string.editor_new_title,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.editor_back))
                    }
                },
                actions = {
                    if (state.isEditing) {
                        IconButton(onClick = {
                            // A recurring occurrence opens the scope dialog (this/series) — a deliberate
                            // choice, no extra confirm. A plain event gets an anti-mistap confirmation.
                            if (state.deleteNeedsScope) onDelete() else confirmDelete = true
                        }) {
                            // Red: deleting is destructive and must read as such at a glance.
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.editor_delete),
                                tint = BrandDanger,
                            )
                        }
                    }
                    // Spelled out and outlined rather than a bare check icon: "Enregistrer" leaves no
                    // doubt about the action, and the border makes it read as the primary button.
                    OutlinedButton(
                        onClick = onSave,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text(stringResource(R.string.editor_save))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                // The app draws edge-to-edge, so the IME inset isn't applied for us: without this the
                // scroll viewport extends under the keyboard and the focused field (description,
                // location…) stays hidden. Padding the scroll container above the IME lets Compose
                // scroll the focused field into view.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = onTitleChange,
                label = { Text(stringResource(R.string.editor_title_label)) },
                singleLine = true,
                isError = state.error == EditorError.BLANK_TITLE,
                supportingText = if (state.error == EditorError.BLANK_TITLE) {
                    { Text(stringResource(R.string.editor_error_blank_title)) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.editor_all_day), modifier = Modifier.weight(1f))
                Switch(checked = state.allDay, onCheckedChange = onAllDayChange)
            }

            CalendarDropdown(
                calendars = state.calendars,
                selectedId = state.selectedCalendarId,
                onSelect = onCalendarSelect,
            )

            ColorPickerRow(selected = state.colorOverride, onSelect = onColorChange)

            DateTimeRow(
                label = stringResource(R.string.editor_starts),
                dateText = formatDate(state.startDateTime.toLocalDate(), locale),
                timeText = formatTime(state.startDateTime.toLocalTime(), locale),
                showTime = !state.allDay,
                onDateClick = { datePickerTarget = EditTarget.START },
                onTimeClick = { timePickerTarget = EditTarget.START },
            )

            DateTimeRow(
                label = stringResource(R.string.editor_ends),
                dateText = formatDate(state.endDateTime.toLocalDate(), locale),
                timeText = formatTime(state.endDateTime.toLocalTime(), locale),
                showTime = !state.allDay,
                onDateClick = { datePickerTarget = EditTarget.END },
                onTimeClick = { timePickerTarget = EditTarget.END },
            )

            if (state.error == EditorError.END_BEFORE_START) {
                Text(
                    text = stringResource(R.string.editor_error_end_before_start),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }

            RecurrenceDropdown(selected = state.recurrenceFreq, onSelect = onRecurrenceSelect)

            state.recurrenceFreq?.let { freq ->
                AdvancedRecurrenceSection(
                    freq = freq,
                    interval = state.recurrenceInterval,
                    byWeekdays = state.recurrenceByWeekdays,
                    end = state.recurrenceEnd,
                    count = state.recurrenceCount,
                    untilDate = state.recurrenceUntilDate,
                    locale = locale,
                    onIntervalChange = onRecurrenceIntervalChange,
                    onToggleWeekday = onToggleRecurrenceWeekday,
                    onEndChange = onRecurrenceEndChange,
                    onCountChange = onRecurrenceCountChange,
                    onPickUntilDate = { datePickerTarget = EditTarget.RECURRENCE_UNTIL },
                )
            }

            RemindersSection(
                reminderMinutes = state.reminderMinutes,
                onAdd = onAddReminder,
                onRemove = onRemoveReminder,
            )

            OutlinedTextField(
                value = state.description,
                onValueChange = onDescriptionChange,
                label = { Text(stringResource(R.string.editor_description)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.address,
                onValueChange = onAddressChange,
                label = { Text(stringResource(R.string.editor_address)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Postal code and city share a row: the code is short, and they are always read together.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.postalCode,
                    onValueChange = onPostalCodeChange,
                    label = { Text(stringResource(R.string.editor_postal_code)) },
                    singleLine = true,
                    modifier = Modifier.weight(POSTAL_CODE_WEIGHT),
                )
                OutlinedTextField(
                    value = state.city,
                    onValueChange = onCityChange,
                    label = { Text(stringResource(R.string.editor_city)) },
                    singleLine = true,
                    modifier = Modifier.weight(CITY_WEIGHT),
                )
            }

            OutlinedTextField(
                value = state.location,
                onValueChange = onLocationChange,
                label = { Text(stringResource(R.string.editor_location)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            GpsField(
                value = state.gpsCoordinates,
                onValueChange = onGpsCoordinatesChange,
                label = state.location.ifBlank { state.title },
            )

            if (state.error == EditorError.SAVE_FAILED) {
                Text(
                    text = stringResource(R.string.editor_error_save_failed),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    datePickerTarget?.let { target ->
        val initialDate = when (target) {
            EditTarget.START -> state.startDateTime.toLocalDate()
            EditTarget.END -> state.endDateTime.toLocalDate()
            EditTarget.RECURRENCE_UNTIL -> state.recurrenceUntilDate
        }
        DatePickerModal(
            initialDate = initialDate,
            onConfirm = { date ->
                when (target) {
                    EditTarget.START -> onStartDateChange(date)
                    EditTarget.END -> onEndDateChange(date)
                    EditTarget.RECURRENCE_UNTIL -> onRecurrenceUntilDateChange(date)
                }
                datePickerTarget = null
            },
            onDismiss = { datePickerTarget = null },
        )
    }

    if (timePickerTarget != null) {
        val current = if (timePickerTarget == EditTarget.START) state.startDateTime else state.endDateTime
        TimePickerModal(
            initialTime = current.toLocalTime(),
            onConfirm = { hour, minute ->
                if (timePickerTarget == EditTarget.START) onStartTimeChange(hour, minute) else onEndTimeChange(hour, minute)
                timePickerTarget = null
            },
            onDismiss = { timePickerTarget = null },
        )
    }
}

@Composable
private fun DateTimeRow(
    label: String,
    dateText: String,
    timeText: String,
    showTime: Boolean,
    onDateClick: () -> Unit,
    onTimeClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onDateClick) { Text(dateText) }
        if (showTime) {
            OutlinedButton(onClick = onTimeClick) { Text(timeText) }
        }
    }
}

@Composable
private fun CalendarDropdown(
    calendars: List<Calendar>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = calendars.firstOrNull { it.id == selectedId }?.name.orEmpty()
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.editor_calendar)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            calendars.forEach { calendar ->
                DropdownMenuItem(
                    text = { Text(calendar.name) },
                    onClick = {
                        onSelect(calendar.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun RecurrenceDropdown(
    selected: RecurrenceFreq?,
    onSelect: (RecurrenceFreq?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options: List<RecurrenceFreq?> = listOf(null) + RecurrenceFreq.entries
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = stringResource(recurrenceLabelRes(selected)),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.editor_recurrence)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { freq ->
                DropdownMenuItem(
                    text = { Text(stringResource(recurrenceLabelRes(freq))) },
                    onClick = {
                        onSelect(freq)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun DatePickerModal(
    initialDate: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { millis ->
                    onConfirm(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                }
            }) { Text(stringResource(R.string.editor_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

@Composable
private fun TimePickerModal(
    initialTime: LocalTime,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val pickerState = rememberTimePickerState(
        initialHour = initialTime.hour,
        initialMinute = initialTime.minute,
        is24Hour = is24Hour,
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(pickerState.hour, pickerState.minute) }) {
                Text(stringResource(R.string.editor_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) }
        },
        text = { TimePicker(state = pickerState) },
    )
}

@Composable
private fun ColorPickerRow(selected: CalendarColor?, onSelect: (CalendarColor?) -> Unit) {
    val ringColor = MaterialTheme.colorScheme.onSurface
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.editor_color),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // "Automatic" = inherit the calendar colour (null override): an outlined empty swatch.
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .border(if (selected == null) 3.dp else 1.dp, ringColor, CircleShape)
                    .clickable { onSelect(null) },
            )
            CalendarColor.entries.forEach { color ->
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(color.argb))
                        .then(
                            if (color == selected) Modifier.border(3.dp, ringColor, CircleShape) else Modifier,
                        )
                        .clickable { onSelect(color) },
                )
            }
        }
    }
}

@Composable
private fun AdvancedRecurrenceSection(
    freq: RecurrenceFreq,
    interval: Int,
    byWeekdays: Set<Weekday>,
    end: RecurrenceEnd,
    count: Int,
    untilDate: LocalDate,
    locale: Locale,
    onIntervalChange: (Int) -> Unit,
    onToggleWeekday: (Weekday) -> Unit,
    onEndChange: (RecurrenceEnd) -> Unit,
    onCountChange: (Int) -> Unit,
    onPickUntilDate: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Interval — "every N days/weeks/months/years".
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.recurrence_every))
            OutlinedTextField(
                value = interval.toString(),
                onValueChange = { it.trim().toIntOrNull()?.let(onIntervalChange) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(88.dp),
            )
            Text(intervalUnitLabel(context, freq))
        }

        // Weekly BYDAY chips.
        if (freq == RecurrenceFreq.WEEKLY) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Weekday.entries.forEach { weekday ->
                    FilterChip(
                        selected = weekday in byWeekdays,
                        onClick = { onToggleWeekday(weekday) },
                        label = { Text(weekdayNarrow(weekday, locale)) },
                    )
                }
            }
        }

        // End of recurrence.
        Text(
            text = stringResource(R.string.recurrence_ends_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = end == RecurrenceEnd.NEVER, onClick = { onEndChange(RecurrenceEnd.NEVER) })
            Text(stringResource(R.string.recurrence_end_never))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(selected = end == RecurrenceEnd.AFTER_COUNT, onClick = { onEndChange(RecurrenceEnd.AFTER_COUNT) })
            Text(stringResource(R.string.recurrence_end_after))
            OutlinedTextField(
                value = count.toString(),
                onValueChange = { it.trim().toIntOrNull()?.let(onCountChange) },
                singleLine = true,
                enabled = end == RecurrenceEnd.AFTER_COUNT,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(88.dp),
            )
            Text(stringResource(R.string.recurrence_occurrences))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(selected = end == RecurrenceEnd.ON_DATE, onClick = { onEndChange(RecurrenceEnd.ON_DATE) })
            Text(stringResource(R.string.recurrence_end_on))
            OutlinedButton(onClick = onPickUntilDate, enabled = end == RecurrenceEnd.ON_DATE) {
                Text(formatDate(untilDate, locale))
            }
        }
    }
}

/**
 * GPS coordinates + a button handing them to the phone's maps app via a `geo:` link. The button only
 * appears once the text is actually usable, so it can never fire an intent that goes nowhere — and
 * the app itself needs no location nor network permission for this.
 */
@Composable
private fun GpsField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    val context = LocalContext.current
    val usable = remember(value) { GeoLink.isUsable(value) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.editor_gps)) },
        placeholder = { Text(stringResource(R.string.editor_gps_hint)) },
        supportingText = { Text(stringResource(R.string.editor_gps_help)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        singleLine = true,
        trailingIcon = {
            if (usable) {
                IconButton(onClick = { openOnMap(context, value, label) }) {
                    Icon(Icons.Filled.Place, stringResource(R.string.editor_gps_open))
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Hands the coordinates to whatever maps app is installed; silently no-ops if there is none. */
private fun openOnMap(context: android.content.Context, coordinates: String, label: String) {
    val uri = GeoLink.fromCoordinates(coordinates, label) ?: return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
    runCatching { context.startActivity(intent) }
        .onFailure { Timber.w(it, "EventEditor: no maps app to open a geo: link") }
}

@Composable
private fun RemindersSection(
    reminderMinutes: List<Int>,
    onAdd: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.editor_reminders),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        reminderMinutes.forEach { minutes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(reminderLabel(context, minutes), modifier = Modifier.weight(1f))
                IconButton(onClick = { onRemove(minutes) }) {
                    Icon(Icons.Filled.Close, stringResource(R.string.editor_remove_reminder))
                }
            }
        }
        var expanded by remember { mutableStateOf(false) }
        var showCustomDialog by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { expanded = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(
                    text = stringResource(R.string.editor_add_reminder),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                REMINDER_PRESETS.filter { it !in reminderMinutes }.forEach { minutes ->
                    DropdownMenuItem(
                        text = { Text(reminderLabel(context, minutes)) },
                        onClick = {
                            onAdd(minutes)
                            expanded = false
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reminder_custom)) },
                    onClick = {
                        expanded = false
                        showCustomDialog = true
                    },
                )
            }
        }
        if (showCustomDialog) {
            CustomReminderDialog(
                existing = reminderMinutes,
                onConfirm = {
                    onAdd(it)
                    showCustomDialog = false
                },
                onDismiss = { showCustomDialog = false },
            )
        }
    }
}

/** Free-form reminder: any value, in minutes / hours / days before the event start. */
@Composable
private fun CustomReminderDialog(
    existing: List<Int>,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var amount by remember { mutableStateOf("10") }
    var unit by remember { mutableStateOf(ReminderUnit.MINUTES) }
    val value = amount.toIntOrNull()
    val minutes = value?.let { it * unit.minutes }
    val duplicate = minutes != null && minutes in existing
    val valid = value != null && value >= 0 && minutes != null && minutes <= MAX_REMINDER_MINUTES && !duplicate

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reminder_custom)) },
        text = {
            Column {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { input ->
                        if (input.length <= 4 && input.all { it.isDigit() }) amount = input
                    },
                    label = { Text(stringResource(R.string.reminder_custom_amount)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = amount.isNotEmpty() && !valid,
                    supportingText = when {
                        duplicate -> { { Text(stringResource(R.string.reminder_custom_duplicate)) } }
                        minutes != null && minutes > MAX_REMINDER_MINUTES -> {
                            { Text(stringResource(R.string.reminder_custom_too_far)) }
                        }
                        else -> null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReminderUnit.entries.forEach { entry ->
                        FilterChip(
                            selected = unit == entry,
                            onClick = { unit = entry },
                            label = { Text(stringResource(entry.labelRes)) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { minutes?.let(onConfirm) }, enabled = valid) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private enum class ReminderUnit(val minutes: Int, val labelRes: Int) {
    MINUTES(1, R.string.reminder_unit_minutes),
    HOURS(60, R.string.reminder_unit_hours),
    DAYS(24 * 60, R.string.reminder_unit_days),
}

/** A reminder can't sensibly fire more than ~4 weeks before the event. */
private const val MAX_REMINDER_MINUTES = 28 * 24 * 60

// A postal code is short; the city gets the rest of the row.
private const val POSTAL_CODE_WEIGHT = 1f
private const val CITY_WEIGHT = 2f

// --- helpers ----------------------------------------------------------------

/** Reminder presets, in minutes-before-start. */
private val REMINDER_PRESETS = listOf(0, 5, 10, 15, 30, 60, 24 * 60)

private fun reminderLabel(context: android.content.Context, minutes: Int): String = when {
    minutes == 0 -> context.getString(R.string.reminder_at_time)
    minutes % (24 * 60) == 0 -> context.getString(R.string.reminder_days, minutes / (24 * 60))
    minutes % 60 == 0 -> context.getString(R.string.reminder_hours, minutes / 60)
    else -> context.getString(R.string.reminder_minutes, minutes)
}

private fun intervalUnitLabel(context: android.content.Context, freq: RecurrenceFreq): String =
    context.getString(
        when (freq) {
            RecurrenceFreq.DAILY -> R.string.recurrence_unit_days
            RecurrenceFreq.WEEKLY -> R.string.recurrence_unit_weeks
            RecurrenceFreq.MONTHLY -> R.string.recurrence_unit_months
            RecurrenceFreq.YEARLY -> R.string.recurrence_unit_years
        },
    )

private fun weekdayNarrow(weekday: Weekday, locale: Locale): String =
    DayOfWeek.of(weekday.isoValue).getDisplayName(TextStyle.NARROW, locale)

private fun recurrenceLabelRes(freq: RecurrenceFreq?): Int = when (freq) {
    null -> R.string.recurrence_none
    RecurrenceFreq.DAILY -> R.string.recurrence_daily
    RecurrenceFreq.WEEKLY -> R.string.recurrence_weekly
    RecurrenceFreq.MONTHLY -> R.string.recurrence_monthly
    RecurrenceFreq.YEARLY -> R.string.recurrence_yearly
}

private fun formatDate(date: LocalDate, locale: Locale): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

private fun formatTime(time: LocalTime, locale: Locale): String =
    time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
