package com.filestech.agenda_tech.ui.navigation

/**
 * Navigation destinations. String-based for now (no kotlinx-serialization dependency in the
 * scaffold); each future screen adds a `const` here and a `composable(...)` in
 * [com.filestech.agenda_tech.ui.AppRoot].
 *
 * Phase-2 destinations still to add:
 *   - WEEK / DAY / AGENDA  (the other calendar views)
 *   - EVENT_EDITOR         (create / edit, arg: eventId — next step)
 *   - CALENDARS            (manage local calendars)
 *   - SETTINGS
 */
object Routes {
    const val MONTH = "month"
}
