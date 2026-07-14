package com.filestech.agenda_tech.ui.navigation

/**
 * Navigation destinations. String-based for now (no kotlinx-serialization dependency in the
 * scaffold); each future screen adds a `const` here and a `composable(...)` in
 * [com.filestech.agenda_tech.ui.AppRoot].
 *
 * Phase-2 destinations the NavHost is structured to receive without a rewrite:
 *   - MONTH / WEEK / DAY / AGENDA  (the calendar views)
 *   - EVENT_EDITOR                 (create / edit, arg: eventId)
 *   - CALENDARS                    (manage local calendars)
 *   - SETTINGS
 */
object Routes {
    const val HOME = "home"
}
