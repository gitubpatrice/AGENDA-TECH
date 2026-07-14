package com.filestech.agenda_tech.ui.navigation

/**
 * Navigation destinations. String-based for now (no kotlinx-serialization dependency in the
 * scaffold); each future screen adds a `const` here and a `composable(...)` in
 * [com.filestech.agenda_tech.ui.AppRoot].
 *
 * Phase-2 destinations still to add:
 *   - AGENDA               (list view)
 *   - CALENDARS            (manage local calendars)
 *   - SETTINGS
 */
object Routes {
    const val MONTH = "month"
    const val WEEK = "week"
    const val DAY = "day"

    const val EDITOR = "editor"
    const val ARG_EVENT_ID = "eventId"
    const val ARG_DATE = "date"

    const val SETTINGS = "settings"
    const val ABOUT = "about"

    /** Full pattern (optional args) registered by the NavHost. */
    const val EDITOR_PATTERN = "$EDITOR?$ARG_EVENT_ID={$ARG_EVENT_ID}&$ARG_DATE={$ARG_DATE}"

    /** Open the editor to create a new event, pre-filling the given day. */
    fun editorForNew(dateEpochDay: Long): String = "$EDITOR?$ARG_DATE=$dateEpochDay"

    /** Open the editor to edit an existing event. */
    fun editorForEdit(eventId: Long): String = "$EDITOR?$ARG_EVENT_ID=$eventId"
}
