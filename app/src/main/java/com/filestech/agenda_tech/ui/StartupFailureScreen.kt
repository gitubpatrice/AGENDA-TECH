package com.filestech.agenda_tech.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.agenda_tech.R

/**
 * Shown when the database refuses to open, instead of letting the process die behind a splash.
 *
 * ## Why it exists (audit DR-1)
 *
 * Audit D1 removed `.fallbackToDestructiveMigrationOnDowngrade(false)`, which was arming the very
 * erasure its comment claimed to forbid. That was right — but it was also the only thing that ever
 * *recovered* from an unopenable database, and nothing replaced it. Room now throws, and an uncaught
 * throw at startup means the app dies behind a splash that never lifts, on every launch, with
 * `allowBackup=false` and no way to export anything because it never starts. The only way out was
 * "clear app data": the same erasure, inflicted by hand, with no explanation.
 *
 * So the trade stands — **the agenda is not touched** — and what this screen adds is the part that
 * was missing: saying so.
 *
 * It deliberately offers **no button**. Every action that would help here (reinstall the newer
 * version, or clear the data) lives outside the app, and a "reset" button on this screen would be a
 * one-tap path to destroying the only copy of an agenda that is, at this point, still perfectly
 * intact. The message names the likely cause instead, which is the thing the user can act on.
 */
@Composable
fun StartupFailureScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.startup_failure_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.startup_failure_body),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
