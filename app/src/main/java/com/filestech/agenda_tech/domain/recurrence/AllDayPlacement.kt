package com.filestech.agenda_tech.domain.recurrence

import com.filestech.agenda_tech.core.time.DAY_MILLIS
import com.filestech.agenda_tech.core.time.TimeZones
import com.filestech.agenda_tech.domain.model.Event
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/*
 * Where an occurrence sits on the phone's calendar — a question whose answer is not its instants for
 * one kind of event: the all-day one.
 *
 * An all-day event is a run of DATES. It is stored like every event, as two instants, and those are the
 * midnights of the zone it was created in ([Event.timeZoneId]): the editor, the `.ics` import and the
 * device import all compute them so. On a phone set to another zone, those midnights fall inside a day —
 * a holiday created in Paris ran from 18:00 to 18:00 in New York — and every view that compared it with
 * the phone's midnights showed it on two dates, while the agenda filed it under the day before. The
 * editor read the same instants on the phone's clock too, so saving such an event unchanged moved it by a
 * day.
 *
 * The functions below read an all-day event's dates in its own zone and place them on the phone's
 * midnights. Every view, the widget, the reminders and the editor go through them: one rule, not a copy
 * per screen. A timed event happens at an instant every zone agrees on, so its instants are already its
 * place and come back unchanged.
 *
 * The stored instants remain the occurrence's IDENTITY — what an `EXDATE`, an override's original start
 * and a reminder's alarm name it by. Only its place on the calendar moves.
 */

/**
 * How far an all-day occurrence's place on the calendar can be from its stored instants. The widest gap
 * between two zones' midnights is 26 hours (UTC−12 to UTC+14); two days leave room for a daylight-saving
 * hour on either side. A query by instants widens its window by this much to be sure of every all-day
 * occurrence that may land in it.
 */
const val ALL_DAY_PLACEMENT_BOUND_MILLIS: Long = 2 * DAY_MILLIS

/**
 * The zone this event's instants read as dates in, on a phone set to [deviceZone]: its own zone for an
 * all-day event, the phone's for the rest. A stored zone nothing can read falls back to UTC, as in
 * [RecurrenceExpander], so both agree on the dates.
 */
fun Event.dateZone(deviceZone: ZoneId): ZoneId =
    if (allDay) TimeZones.resolveOrNull(timeZoneId) ?: ZoneOffset.UTC else deviceZone

/** Where an occurrence of this event starting at [occurrenceStartUtcMillis] begins on [deviceZone]'s calendar. */
fun Event.shownStartUtcMillis(occurrenceStartUtcMillis: Long, deviceZone: ZoneId): Long {
    if (!allDay) return occurrenceStartUtcMillis
    return dateAt(occurrenceStartUtcMillis, dateZone(deviceZone)).startIn(deviceZone)
}

/** Where this occurrence begins on [deviceZone]'s calendar: midnight of its first date, for an all-day one. */
fun EventOccurrence.shownStartUtcMillis(deviceZone: ZoneId): Long =
    event.shownStartUtcMillis(startUtcMillis, deviceZone)

/**
 * Where this occurrence ends on [deviceZone]'s calendar: for an all-day one, midnight after its last date.
 * A stored end that is not a midnight (a row written by hand) still covers the day it falls in, and an
 * all-day occurrence always covers at least its first date.
 */
fun EventOccurrence.shownEndUtcMillis(deviceZone: ZoneId): Long {
    if (!event.allDay) return endUtcMillis
    val ownZone = event.dateZone(deviceZone)
    val firstDate = dateAt(startUtcMillis, ownZone)
    val endDate = dateAt(endUtcMillis, ownZone)
    val dayAfter = if (endDate.startIn(ownZone) == endUtcMillis) endDate else endDate.plusDays(1)
    return maxOf(dayAfter, firstDate.plusDays(1)).startIn(deviceZone)
}

private fun dateAt(utcMillis: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(utcMillis).atZone(zone).toLocalDate()

private fun LocalDate.startIn(zone: ZoneId): Long = atStartOfDay(zone).toInstant().toEpochMilli()
