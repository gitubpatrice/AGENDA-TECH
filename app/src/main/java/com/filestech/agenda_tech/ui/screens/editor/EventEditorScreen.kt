package com.filestech.agenda_tech.ui.screens.editor

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private enum class EditTarget { START, END }

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
        onRecurrenceSelect = viewModel::onRecurrenceSelect,
        onDescriptionChange = viewModel::onDescriptionChange,
        onLocationChange = viewModel::onLocationChange,
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
    onRecurrenceSelect: (RecurrenceFreq?) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onLocationChange: (String) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    var datePickerTarget by remember { mutableStateOf<EditTarget?>(null) }
    var timePickerTarget by remember { mutableStateOf<EditTarget?>(null) }

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
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Filled.Delete, stringResource(R.string.editor_delete))
                        }
                    }
                    IconButton(onClick = onSave) {
                        Icon(Icons.Filled.Check, stringResource(R.string.editor_save))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
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

            OutlinedTextField(
                value = state.description,
                onValueChange = onDescriptionChange,
                label = { Text(stringResource(R.string.editor_description)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.location,
                onValueChange = onLocationChange,
                label = { Text(stringResource(R.string.editor_location)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
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

    if (datePickerTarget != null) {
        val current = if (datePickerTarget == EditTarget.START) state.startDateTime else state.endDateTime
        DatePickerModal(
            initialDate = current.toLocalDate(),
            onConfirm = { date ->
                if (datePickerTarget == EditTarget.START) onStartDateChange(date) else onEndDateChange(date)
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

// --- helpers ----------------------------------------------------------------

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
