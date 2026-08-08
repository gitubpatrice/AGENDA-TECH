package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.domain.ImportLimits
import com.filestech.agenda_tech.domain.ics.IcsCodec
import com.filestech.agenda_tech.domain.ics.toEvent
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.repository.EventRepository
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import javax.inject.Inject

/**
 * Parses an `.ics` document and inserts its events into the default calendar. Returns the number
 * imported (0 if the file has no valid VEVENT or no calendar exists yet). Reminders/VALARM are not
 * imported (out of scope for this codec).
 */
class ImportEventsUseCase @Inject constructor(
    private val eventRepository: EventRepository,
    private val calendarRepository: CalendarRepository,
) {
    /**
     * The file holds more events than [ImportLimits.MAX_EVENTS].
     *
     * A distinct type, not a generic failure: the screen can then say *why* the file was refused, and
     * "too large" is the one import failure the user can actually do something about.
     */
    class TooManyEvents(val found: Int) :
        Exception("ics file holds $found events, over the ${ImportLimits.MAX_EVENTS} ceiling")

    suspend operator fun invoke(icsText: String, defaultZoneId: String): Int {
        val calendars = calendarRepository.observeAll().first()
        val targetId = (calendars.firstOrNull { it.isDefault } ?: calendars.firstOrNull())?.id ?: return 0

        val zone = runCatching { ZoneId.of(defaultZoneId) }.getOrDefault(ZoneId.systemDefault())
        val parsed = IcsCodec.decode(icsText, zone)
        // Audit S12 — refused WHOLE, before a single row is written. The byte ceiling upstream bounds
        // what is read into memory; it says nothing about how many rows reach the database, and 5 MiB
        // of minimal VEVENT blocks is some 87 000 of them. Importing part of the file would look
        // exactly like importing all of it.
        if (parsed.size > ImportLimits.MAX_EVENTS) throw TooManyEvents(parsed.size)

        // FIAB-1 — idempotent re-import: an event whose VEVENT UID was already imported updates the
        // same row instead of adding a duplicate (events without a UID still insert). Same pattern
        // as the device-calendar import.
        val existing = eventRepository.sourceUidMap(targetId)
        val mapped = parsed.map { icsEvent ->
            val event = icsEvent.toEvent(targetId)
            val existingId = event.sourceUid?.let { existing[it] }
            if (existingId != null) event.copy(id = existingId) else event
        }
        eventRepository.upsertAll(mapped) // PERF-1 — single atomic batch, not N transactions
        return mapped.size
    }
}
