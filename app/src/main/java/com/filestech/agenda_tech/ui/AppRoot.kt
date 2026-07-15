package com.filestech.agenda_tech.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.filestech.agenda_tech.ui.navigation.CalendarView
import com.filestech.agenda_tech.ui.navigation.Routes
import com.filestech.agenda_tech.ui.screens.about.AboutScreen
import com.filestech.agenda_tech.ui.screens.agenda.AgendaScreen
import com.filestech.agenda_tech.ui.screens.backup.BackupScreen
import com.filestech.agenda_tech.ui.deviceimport.DeviceImportScreen
import com.filestech.agenda_tech.ui.screens.calendars.CalendarsScreen
import com.filestech.agenda_tech.ui.screens.editor.EventEditorScreen
import com.filestech.agenda_tech.ui.screens.month.MonthScreen
import com.filestech.agenda_tech.ui.screens.search.SearchScreen
import com.filestech.agenda_tech.ui.screens.settings.SettingsScreen
import com.filestech.agenda_tech.ui.screens.timeline.DayScreen
import com.filestech.agenda_tech.ui.screens.timeline.WeekScreen

/**
 * Root of the Compose tree: owns the [NavHost]. The three calendar views (Month/Week/Day) are
 * siblings switched via the bottom navigation; the editor is pushed on top of whichever view is
 * active.
 */
@Composable
fun AppRoot() {
    val navController = rememberNavController()

    val onAddEvent: (java.time.LocalDate) -> Unit = { date ->
        navController.navigate(Routes.editorForNew(date.toEpochDay()))
    }
    val onOccurrenceClick: (Long, Long) -> Unit = { eventId, occurrenceStart ->
        navController.navigate(Routes.editorForEdit(eventId, occurrenceStart))
    }
    val onSelectView: (CalendarView) -> Unit = { view -> navController.switchCalendarView(view) }

    NavHost(navController = navController, startDestination = Routes.MONTH) {
        composable(Routes.MONTH) {
            MonthScreen(
                onSelectView = onSelectView,
                onAddEvent = onAddEvent,
                onOccurrenceClick = onOccurrenceClick,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenBackup = { navController.navigate(Routes.BACKUP) },
            )
        }
        composable(Routes.WEEK) {
            WeekScreen(onSelectView = onSelectView, onAddEvent = onAddEvent, onOccurrenceClick = onOccurrenceClick)
        }
        composable(Routes.DAY) {
            DayScreen(onSelectView = onSelectView, onAddEvent = onAddEvent, onOccurrenceClick = onOccurrenceClick)
        }
        composable(Routes.AGENDA) {
            AgendaScreen(onSelectView = onSelectView, onAddEvent = onAddEvent, onOccurrenceClick = onOccurrenceClick)
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
                navArgument(Routes.ARG_OCCURRENCE_START) {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) {
            EventEditorScreen(onDone = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
                onOpenCalendars = { navController.navigate(Routes.CALENDARS) },
                onOpenDeviceImport = { navController.navigate(Routes.DEVICE_IMPORT) },
                onOpenBackup = { navController.navigate(Routes.BACKUP) },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.CALENDARS) {
            CalendarsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.DEVICE_IMPORT) {
            DeviceImportScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.BACKUP) {
            BackupScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onOccurrenceClick = onOccurrenceClick,
            )
        }
    }
}

/** Switch between the sibling calendar views, keeping Month as the single back-stack base. */
private fun NavHostController.switchCalendarView(view: CalendarView) {
    navigate(view.route) {
        popUpTo(Routes.MONTH) { inclusive = false }
        launchSingleTop = true
    }
}
