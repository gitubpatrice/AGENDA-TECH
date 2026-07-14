package com.filestech.agenda_tech.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.filestech.agenda_tech.ui.navigation.Routes
import com.filestech.agenda_tech.ui.screens.month.MonthScreen

/**
 * Root of the Compose tree: owns the [NavHost]. Kept deliberately thin so each phase-2 view
 * ([Routes]) plugs in as one more `composable(...)` entry.
 */
@Composable
fun AppRoot() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.MONTH) {
        composable(Routes.MONTH) {
            MonthScreen(
                // Wired to the event editor in the next step (Routes.EVENT_EDITOR).
                onAddEvent = { /* selectedDate */ },
                onOccurrenceClick = { /* eventId */ },
            )
        }
    }
}
