package com.filestech.agenda_tech.ui.screens.editor

import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.Weekday
import java.time.LocalDate
import java.time.LocalDateTime

/** Typed validation errors, mapped to localized strings by the screen. */
enum class EditorError { BLANK_TITLE, END_BEFORE_START, SAVE_FAILED }

/** How a recurrence terminates. */
enum class RecurrenceEnd { NEVER, AFTER_COUNT, ON_DATE }

/**
 * Form state for the event editor. Dates/times are held as local `LocalDateTime` in the device
 * zone and converted to UTC instants on save. For an all-day event only the *dates* matter and the
 * end date is inclusive (converted to an exclusive next-midnight boundary when persisted).
 *
 * [recurrenceFreq] null means "does not repeat". [isSaved]/[isDeleted] are one-shot signals the
 * screen consumes to pop back.
 */
data class EventEditorUiState(
    val isEditing: Boolean = false,
    val title: String = "",
    val allDay: Boolean = false,
    val startDateTime: LocalDateTime,
    val endDateTime: LocalDateTime,
    val calendars: List<Calendar> = emptyList(),
    val selectedCalendarId: Long = 0L,
    val recurrenceFreq: RecurrenceFreq? = null,
    val recurrenceInterval: Int = 1,
    /** Weekly BYDAY selection (only meaningful when [recurrenceFreq] is WEEKLY). */
    val recurrenceByWeekdays: Set<Weekday> = emptySet(),
    val recurrenceEnd: RecurrenceEnd = RecurrenceEnd.NEVER,
    val recurrenceCount: Int = 10,
    val recurrenceUntilDate: LocalDate,
    /** Reminders as minutes-before-start, ascending and de-duplicated. */
    val reminderMinutes: List<Int> = emptyList(),
    val description: String = "",
    val location: String = "",
    val error: EditorError? = null,
    val isSaved: Boolean = false,
    val isDeleted: Boolean = false,
) {
    val selectedCalendar: Calendar?
        get() = calendars.firstOrNull { it.id == selectedCalendarId }
}
