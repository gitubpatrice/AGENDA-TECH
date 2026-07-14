package com.filestech.agenda_tech.data.repository

import com.filestech.agenda_tech.data.local.db.entity.CalendarEntity
import com.filestech.agenda_tech.data.local.db.entity.EventEntity
import com.filestech.agenda_tech.data.local.db.entity.ReminderEntity
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.model.Reminder
import com.filestech.agenda_tech.domain.model.Weekday

/**
 * Pure, isolated mapping between Room entities and domain models. The domain layer never imports
 * a Room type; this file is the single crossing point. Recurrence is (de)composed here: the
 * structured `rrule_*` columns become a [RecurrenceRule] value object and back.
 */

// --- Calendar ---------------------------------------------------------------

internal fun CalendarEntity.toDomain(): Calendar = Calendar(
    id = id,
    name = name,
    color = color,
    isVisible = visible,
    isDefault = isDefault,
)

internal fun Calendar.toEntity(createdAt: Long): CalendarEntity = CalendarEntity(
    id = id,
    name = name,
    color = color,
    visible = isVisible,
    isDefault = isDefault,
    createdAt = createdAt,
)

// --- Event ------------------------------------------------------------------

internal fun EventEntity.toDomain(): Event = Event(
    id = id,
    calendarId = calendarId,
    title = title,
    description = description,
    location = location,
    startUtcMillis = startUtcMillis,
    endUtcMillis = endUtcMillis,
    timeZoneId = timeZoneId,
    allDay = allDay,
    recurrence = rruleFreq?.let { freq ->
        RecurrenceRule(
            freq = freq,
            interval = rruleInterval,
            byWeekdays = parseWeekdays(rruleByWeekdays),
            count = rruleCount,
            untilUtcMillis = rruleUntilUtcMillis,
            exDatesUtcMillis = parseEpochList(rruleExDates),
        )
    },
    colorOverride = colorOverride,
)

internal fun Event.toEntity(createdAt: Long, updatedAt: Long): EventEntity = EventEntity(
    id = id,
    calendarId = calendarId,
    title = title,
    description = description,
    location = location,
    startUtcMillis = startUtcMillis,
    endUtcMillis = endUtcMillis,
    timeZoneId = timeZoneId,
    allDay = allDay,
    rruleFreq = recurrence?.freq,
    rruleInterval = recurrence?.interval ?: 1,
    rruleByWeekdays = recurrence?.byWeekdays?.let(::formatWeekdays).orEmpty(),
    rruleCount = recurrence?.count,
    rruleUntilUtcMillis = recurrence?.untilUtcMillis,
    rruleExDates = recurrence?.exDatesUtcMillis?.let(::formatEpochList).orEmpty(),
    colorOverride = colorOverride,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

// --- Reminder ---------------------------------------------------------------

internal fun ReminderEntity.toDomain(): Reminder = Reminder(
    id = id,
    eventId = eventId,
    minutesBefore = minutesBefore,
)

internal fun Reminder.toEntity(): ReminderEntity = ReminderEntity(
    id = id,
    eventId = eventId,
    minutesBefore = minutesBefore,
)

// --- CSV helpers for the structured recurrence columns ----------------------

private fun parseWeekdays(csv: String): Set<Weekday> =
    csv.split(',').mapNotNull { it.trim().toIntOrNull() }.map(Weekday::fromIso).toSet()

private fun formatWeekdays(days: Set<Weekday>): String =
    days.map { it.isoValue }.sorted().joinToString(separator = ",")

private fun parseEpochList(csv: String): List<Long> =
    csv.split(',').mapNotNull { it.trim().toLongOrNull() }

private fun formatEpochList(list: List<Long>): String =
    list.joinToString(separator = ",")
