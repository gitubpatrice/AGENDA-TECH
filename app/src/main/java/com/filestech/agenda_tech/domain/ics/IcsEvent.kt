package com.filestech.agenda_tech.domain.ics

import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceRule

/**
 * A calendar event in the neutral shape the ICS codec works with — no persistence identity
 * ([Event.id]) or calendar membership ([Event.calendarId]), which are meaningless in an `.ics` file.
 */
data class IcsEvent(
    val title: String,
    val description: String?,
    val location: String?,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
    val timeZoneId: String,
    val allDay: Boolean,
    val recurrence: RecurrenceRule?,
    /** RFC 5545 `UID` of the source VEVENT, used to update the same row on re-import (idempotence). */
    val uid: String? = null,
)

/** Drop the identity/membership for export. */
fun Event.toIcsEvent(): IcsEvent = IcsEvent(
    title = title,
    description = description,
    location = location,
    startUtcMillis = startUtcMillis,
    endUtcMillis = endUtcMillis,
    timeZoneId = timeZoneId,
    allDay = allDay,
    recurrence = recurrence,
)

/** Attach an imported event to a target calendar (a new, unsaved [Event]). */
fun IcsEvent.toEvent(calendarId: Long): Event = Event(
    calendarId = calendarId,
    title = title,
    description = description,
    location = location,
    startUtcMillis = startUtcMillis,
    endUtcMillis = endUtcMillis,
    timeZoneId = timeZoneId,
    allDay = allDay,
    recurrence = recurrence,
    sourceUid = uid?.let { "ics:$it" },
)
