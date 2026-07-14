package com.filestech.agenda_tech.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.ui.graphics.vector.ImageVector
import com.filestech.agenda_tech.R

/** The three calendar views, surfaced in the bottom navigation switcher. */
enum class CalendarView(
    val route: String,
    val icon: ImageVector,
    @param:StringRes val labelRes: Int,
) {
    MONTH(Routes.MONTH, Icons.Filled.CalendarMonth, R.string.view_month),
    WEEK(Routes.WEEK, Icons.Filled.CalendarViewWeek, R.string.view_week),
    DAY(Routes.DAY, Icons.Filled.CalendarViewDay, R.string.view_day),
    AGENDA(Routes.AGENDA, Icons.AutoMirrored.Filled.ViewList, R.string.view_agenda),
}
