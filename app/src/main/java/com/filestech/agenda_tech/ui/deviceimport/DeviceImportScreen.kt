package com.filestech.agenda_tech.ui.deviceimport

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.agenda_tech.R

/**
 * "Import from the device calendar" — one-shot, read-only, no network. Requests READ_CALENDAR,
 * lists the device calendars, lets the user pick which to copy into Agenda Tech, and reports the
 * count. Popped back to Settings once the import completes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceImportScreen(
    onBack: () -> Unit,
    viewModel: DeviceImportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val cleared by viewModel.cleared.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.onPermissionGranted() else viewModel.onPermissionDenied() }

    // Same guard as the disabled back arrow, for the system back gesture (audit U2).
    BackHandler(enabled = importing) { /* swallow: an import is in flight */ }

    // On entry, if the permission is already granted, load straight away.
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.onPermissionGranted()
    }

    // Import finished → report the outcome and return to Settings. A calendar that could not be read
    // or written is surfaced explicitly (audit C5) rather than being reported as "0 events imported".
    LaunchedEffect(result) {
        result?.let {
            val message = if (it.failedCalendars > 0) {
                context.getString(R.string.device_import_partial, it.events, it.calendars, it.failedCalendars)
            } else {
                context.getString(R.string.device_import_done, it.events, it.calendars)
            }
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            viewModel.consumeResult()
            onBack()
        }
    }

    LaunchedEffect(cleared) {
        if (cleared) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.device_import_cleared),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            viewModel.consumeCleared()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.device_import_title)) },
                navigationIcon = {
                    // Disabled while importing: leaving would destroy the ViewModel, cancel the
                    // transaction and silently discard the work in progress (audit U2).
                    IconButton(onClick = onBack, enabled = !importing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.editor_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                DeviceImportViewModel.UiState.NeedPermission -> PermissionPrompt(
                    onGrant = { permissionLauncher.launch(Manifest.permission.READ_CALENDAR) },
                )

                DeviceImportViewModel.UiState.Loading -> CircularProgressIndicator()

                DeviceImportViewModel.UiState.Empty -> CenteredMessage(stringResource(R.string.device_import_empty))

                is DeviceImportViewModel.UiState.Ready -> ReadyContent(
                    calendars = s.calendars,
                    selected = selected,
                    importing = importing,
                    onToggle = viewModel::toggle,
                    onImport = viewModel::import,
                    onClearImported = { confirmClear = true },
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.device_import_clear_confirm_title)) },
            text = { Text(stringResource(R.string.device_import_clear_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearImported()
                }) {
                    Text(stringResource(R.string.device_import_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit) {
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.device_import_rationale),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onGrant, modifier = Modifier.padding(top = 20.dp)) {
            Text(stringResource(R.string.device_import_grant))
        }
    }
}

@Composable
private fun ReadyContent(
    calendars: List<com.filestech.agenda_tech.data.device.DeviceCalendar>,
    selected: Set<Long>,
    importing: Boolean,
    onToggle: (Long) -> Unit,
    onImport: () -> Unit,
    onClearImported: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.device_import_pick),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        TextButton(
            onClick = onClearImported,
            enabled = !importing,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(stringResource(R.string.device_import_clear))
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(calendars, key = { it.id }) { calendar ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // UX-NEW-1 — one toggleable node (Role.Checkbox) instead of a clickable Row +
                        // a separately-focusable Checkbox, so TalkBack announces the row once.
                        .toggleable(
                            value = calendar.id in selected,
                            enabled = !importing,
                            role = Role.Checkbox,
                            onValueChange = { onToggle(calendar.id) },
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = calendar.id in selected,
                        onCheckedChange = null,
                        enabled = !importing,
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(calendar.displayName, style = MaterialTheme.typography.bodyLarge)
                        if (calendar.accountName.isNotBlank() && calendar.accountName != calendar.displayName) {
                            Text(
                                calendar.accountName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Button(
            onClick = onImport,
            enabled = selected.isNotEmpty() && !importing,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            if (importing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(18.dp)
                        .padding(end = 4.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    stringResource(R.string.device_import_running),
                    modifier = Modifier.padding(start = 8.dp),
                )
            } else {
                Text(stringResource(R.string.device_import_action, selected.size))
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(32.dp),
    )
}
