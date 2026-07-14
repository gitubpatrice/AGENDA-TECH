package com.filestech.agenda_tech.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.filestech.agenda_tech.ui.navigation.Routes
import com.filestech.agenda_tech.ui.screens.editor.EventEditorScreen
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
                onAddEvent = { date -> navController.navigate(Routes.editorForNew(date.toEpochDay())) },
                onOccurrenceClick = { eventId -> navController.navigate(Routes.editorForEdit(eventId)) },
            )
        }
        composable(
            route = Routes.EDITOR_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_EVENT_ID) {
                    type = NavType.LongType
                    defaultValue = -1L
                },
                navArgument(Routes.ARG_DATE) {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) {
            EventEditorScreen(onDone = { navController.popBackStack() })
        }
    }
}
