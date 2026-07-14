package com.filestech.agenda_tech.ui.navigation

/**
 * Navigation destinations. String-based (no kotlinx-serialization dependency); each screen has a
 * `const` here and a matching `composable(...)` in [com.filestech.agenda_tech.ui.AppRoot].
 */
object Routes {
    const val MONTH = "month"
    const val WEEK = "week"
    const val DAY = "day"
    const val AGENDA = "agenda"

    const val EDITOR = "editor"
    const val ARG_EVENT_ID = "eventId"
    const val ARG_DATE = "date"
    const val ARG_OCCURRENCE_START = "occurrenceStart"

    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val CALENDARS = "calendars"
    const val DEVICE_IMPORT = "device_import"

    /** Full pattern (optional args) registered by the NavHost. */
    const val EDITOR_PATTERN =
        "$EDITOR?$ARG_EVENT_ID={$ARG_EVENT_ID}&$ARG_DATE={$ARG_DATE}&$ARG_OCCURRENCE_START={$ARG_OCCURRENCE_START}"

    /** Open the editor to create a new event, pre-filling the given day. */
    fun editorForNew(dateEpochDay: Long): String = "$EDITOR?$ARG_DATE=$dateEpochDay"

    /** Open the editor to edit an existing event's tapped occurrence. */
    fun editorForEdit(eventId: Long, occurrenceStartUtcMillis: Long): String =
        "$EDITOR?$ARG_EVENT_ID=$eventId&$ARG_OCCURRENCE_START=$occurrenceStartUtcMillis"
}
