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
    sourceId = sourceId,
)

internal fun Calendar.toEntity(createdAt: Long): CalendarEntity = CalendarEntity(
    id = id,
    name = name,
    color = color,
    visible = isVisible,
    isDefault = isDefault,
    createdAt = createdAt,
    sourceId = sourceId,
)

// --- Event ------------------------------------------------------------------

internal fun EventEntity.toDomain(): Event = Event(
    id = id,
    calendarId = calendarId,
    title = title,
    description = description,
    location = location,
    address = address,
    postalCode = postalCode,
    city = city,
    gpsCoordinates = gpsCoordinates,
    startUtcMillis = startUtcMillis,
    endUtcMillis = endUtcMillis,
    timeZoneId = timeZoneId,
    allDay = allDay,
    recurrence = rruleFreq?.let { freq ->
        RecurrenceRule(
            freq = freq,
            // Audit F1 — clamped on the way out of the DB too, so a row an affected build already
            // stored is healed on read instead of throwing in RecurrenceRule.init.
            //
            // Audit 2026-09-11 — `RecurrenceRule.init` leve sur TROIS conditions ; une seule etait
            // amortie ici. Les deux autres (`count XOR until`, `count >= 1`) laissaient une ligne
            // malformee remonter jusqu'au constructeur, et comme AUCUN Flow du depot ne porte de
            // `.catch`, l'exception traversait `observeForExpansion` -> `combine` -> `stateIn` et
            // tuait le processus : l'application plantait au lancement, sans moyen d'atteindre la
            // ligne fautive pour la supprimer. C'est exactement le scenario que la KDoc de
            // MAX_INTERVAL decrit, par deux portes qu'elle ne fermait pas.
            //
            // Inatteignable par une ligne ecrite par cette version — le domaine garantit les
            // invariants avant `toEntity`. Reparer a la lecture coute trois lignes et ferme la
            // question sans avoir a prouver qu'aucune version passee n'a pu ecrire ca.
            interval = rruleInterval.coerceIn(1, RecurrenceRule.MAX_INTERVAL),
            byWeekdays = parseWeekdays(rruleByWeekdays),
            // `count` invalide (0 ou negatif) = pas de borne, plutot qu'une borne absurde.
            count = rruleCount?.takeIf { it >= 1 },
            // COUNT et UNTIL sont exclusifs : si les deux sont presents, COUNT gagne — meme ordre de
            // priorite que les deux parseurs RRULE (IcsCodec et DeviceEventMapper).
            untilUtcMillis = if (rruleCount?.takeIf { it >= 1 } != null) null else rruleUntilUtcMillis,
            exDatesUtcMillis = parseEpochList(rruleExDates),
        )
    },
    colorOverride = colorOverride,
    recurrenceParentId = recurrenceParentId,
    originalStartUtcMillis = originalStartUtcMillis,
    sourceUid = sourceUid,
    kind = kind,
)

internal fun Event.toEntity(createdAt: Long, updatedAt: Long): EventEntity = EventEntity(
    id = id,
    calendarId = calendarId,
    title = title,
    description = description,
    location = location,
    address = address,
    postalCode = postalCode,
    city = city,
    gpsCoordinates = gpsCoordinates,
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
    recurrenceParentId = recurrenceParentId,
    originalStartUtcMillis = originalStartUtcMillis,
    sourceUid = sourceUid,
    kind = kind,
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
