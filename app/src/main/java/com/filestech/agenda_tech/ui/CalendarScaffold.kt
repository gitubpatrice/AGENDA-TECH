package com.filestech.agenda_tech.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.filestech.agenda_tech.ui.navigation.CalendarView
import com.filestech.agenda_tech.ui.util.DeletionUndoSnackbarHost

/**
 * Shared scaffold for the four calendar views: it owns the bottom navigation that switches between
 * Month / Week / Day / Agenda, so each screen only provides its own top bar, FAB and body.
 */
@Composable
fun CalendarScaffold(
    currentView: CalendarView,
    onSelectView: (CalendarView) -> Unit,
    topBar: @Composable () -> Unit,
    floatingActionButton: @Composable () -> Unit = {},
    /** False while a sheet over the screen shows the undo itself — it would otherwise be hidden under it. */
    undoSnackbar: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = topBar,
        floatingActionButton = floatingActionButton,
        // Here, once, so every view the editor returns to after a deletion offers its undo.
        snackbarHost = { if (undoSnackbar) DeletionUndoSnackbarHost() },
        bottomBar = {
            NavigationBar {
                CalendarView.entries.forEach { view ->
                    NavigationBarItem(
                        selected = view == currentView,
                        onClick = { if (view != currentView) onSelectView(view) },
                        icon = { Icon(view.icon, contentDescription = null) },
                        label = { Text(stringResource(view.labelRes)) },
                    )
                }
            }
        },
        content = content,
    )
}
