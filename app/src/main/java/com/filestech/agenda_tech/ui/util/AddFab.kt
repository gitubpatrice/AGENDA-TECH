package com.filestech.agenda_tech.ui.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * The square "+" button floating over every screen that adds something — an event, a calendar.
 *
 * Filled with the primary colour and its "on" colour, as the lock screen's Unlock button is: the
 * main action of a screen looks the same everywhere in the app. The Material default is the pale
 * primary container, which read as secondary next to the filled buttons.
 *
 * One composable for all of them, so the five screens that show it cannot drift apart again.
 */
@Composable
fun AddFab(onClick: () -> Unit, contentDescription: String) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Icon(Icons.Filled.Add, contentDescription = contentDescription)
    }
}
