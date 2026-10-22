package com.filestech.agenda_tech.domain.ics

import com.filestech.agenda_tech.core.text.BidiSanitizer
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.EventKind
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import java.util.UUID

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
    /**
     * Carried through the non-standard `X-AGENDA-TECH-KIND` property. RFC 5545 has no notion of a
     * birthday, and `X-` is the extension mechanism the spec itself provides for exactly this, so a
     * round-trip through our own `.ics` keeps the cake while any other calendar simply ignores the
     * line and reads a yearly all-day event — which is what it is.
     */
    val kind: EventKind = EventKind.NORMAL,
)

/**
 * Marks an [Event.sourceUid] that is an iCalendar `UID`: read from an imported `.ics`, or given to an
 * event created here the first time it left the app ([newIcsSourceUid]). A device Calendar Provider uid
 * carries no prefix.
 */
const val ICS_SOURCE_PREFIX = "ics:"

/**
 * A new identity for an event created here, given the first time it leaves the app — export or share —
 * and kept from then on (`EventRepository.withIcsIdentities`).
 *
 * Random, and on purpose: up to 1.3.0 the UID was derived from the row id, which every phone numbers
 * from 1, so a friend's shared event 12 named this phone's event 12. An identifier of this phone would
 * have been unique too, but it would follow the user into every file they send.
 */
fun newIcsSourceUid(): String = "$ICS_SOURCE_PREFIX${UUID.randomUUID()}@filestech"

/**
 * Drop the persistence membership for export, but keep a **stable** UID so a re-export of the same
 * event carries the same UID across exports (FIAB-NEW-2) — the event's own [Event.sourceUid], never its
 * list position.
 *
 * Refuses an event with none. The fallback used to be `row-<id>`: the same on every phone, and answered
 * by nothing on this one, so the file came back as a copy, and the copy left with the same UID next
 * time — one more event per round trip (2026-10-22). Whatever writes a file gives its events an identity
 * first (`EventRepository.withIcsIdentities`).
 */
fun Event.toIcsEvent(): IcsEvent {
    val uid = checkNotNull(sourceUid) { "event $id has no identity yet: pass it through withIcsIdentities first" }
    return IcsEvent(
        title = title,
        description = description,
        location = location,
        startUtcMillis = startUtcMillis,
        endUtcMillis = endUtcMillis,
        timeZoneId = timeZoneId,
        allDay = allDay,
        recurrence = recurrence,
        uid = uid.removePrefix(ICS_SOURCE_PREFIX),
        kind = kind,
    )
}

/**
 * This row, rewritten with what [file] says about it — and only that.
 *
 * A re-import updates the event the agenda already holds. Its calendar, its colour, the place details
 * typed here and its link to a series are not in the file: rebuilding the row from the file would move
 * it to the default calendar and drop them.
 *
 * A text the file carries exactly as this app wrote it keeps the row's own. Reading strips bidi controls
 * and cuts at [BidiSanitizer.MAX_FIELD_CHARS], which the editor does not: a round trip would otherwise
 * shorten a long description. [placesWritten] are the `LOCATION`s this app writes for the row — the place
 * name in an export, the whole place line in a share — so a share that comes back does not turn the name
 * into "name, street, town" beside the address fields it was made from.
 */
fun Event.updatedFrom(file: IcsEvent, placesWritten: List<String?> = listOf(location)): Event = copy(
    title = if (asReadBack(title) == file.title) title else file.title,
    description = if (asReadBack(description) == file.description) description else file.description,
    location = if (placesWritten.any { asReadBack(it) == file.location }) location else file.location,
    startUtcMillis = file.startUtcMillis,
    endUtcMillis = file.endUtcMillis,
    timeZoneId = file.timeZoneId,
    allDay = file.allDay,
    recurrence = file.recurrence,
    kind = file.kind,
)

/** A text as the import reads back what the export wrote: blank is not written, the rest is sanitised. */
private fun asReadBack(text: String?): String? = text?.takeIf { it.isNotBlank() }?.let(BidiSanitizer::stripAndCap)

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
    sourceUid = uid?.let { "$ICS_SOURCE_PREFIX$it" },
    kind = kind,
)
