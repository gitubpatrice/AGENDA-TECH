package com.filestech.agenda_tech.ui.deviceimport

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.onPermissionGranted() else viewModel.onPermissionDenied() }

    // On entry, if the permission is already granted, load straight away.
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.onPermissionGranted()
    }

    // Import finished → toast the count and return to Settings.
    LaunchedEffect(result) {
        result?.let {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.device_import_done, it.events, it.calendars),
                android.widget.Toast.LENGTH_LONG,
            ).show()
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
                    IconButton(onClick = onBack) {
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
                    onClearImported = viewModel::clearImported,
                )
            }
        }
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
                        .clickable(enabled = !importing) { onToggle(calendar.id) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = calendar.id in selected,
                        onCheckedChange = { onToggle(calendar.id) },
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
