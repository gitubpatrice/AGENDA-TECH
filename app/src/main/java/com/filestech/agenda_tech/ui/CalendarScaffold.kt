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

/**
 * Shared scaffold for the three calendar views: it owns the bottom navigation that switches between
 * Month / Week / Day, so each screen only provides its own top bar, FAB and body.
 */
@Composable
fun CalendarScaffold(
    currentView: CalendarView,
    onSelectView: (CalendarView) -> Unit,
    topBar: @Composable () -> Unit,
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = topBar,
        floatingActionButton = floatingActionButton,
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
