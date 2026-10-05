package com.filestech.agenda_tech.ui.util

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.filestech.agenda_tech.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The date picker dialog, shared by the event editor and the month view's "go to a date".
 *
 * The Material picker speaks in UTC midnights, whatever the device zone; the conversion to and from
 * [LocalDate] lives here so no caller can get it half right.
 */
@Composable
fun DatePickerModal(
    initialDate: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    yearRange: IntRange = DatePickerDefaults.YearRange,
) {
    val initialMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis, yearRange = yearRange)
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
