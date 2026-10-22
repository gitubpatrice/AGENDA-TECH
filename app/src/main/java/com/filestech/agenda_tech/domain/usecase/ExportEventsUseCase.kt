package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.domain.ics.IcsCodec
import com.filestech.agenda_tech.domain.ics.toIcsEvent
import com.filestech.agenda_tech.domain.repository.EventRepository
import javax.inject.Inject

/** Serialises the whole agenda to an RFC 5545 `.ics` document. Returns the text and event count. */
class ExportEventsUseCase @Inject constructor(
    private val eventRepository: EventRepository,
) {
    suspend operator fun invoke(nowUtcMillis: Long): Result {
        // By id: rows sharing a UID then leave in the order the import pairs them back with
        // (ImportEventsUseCase, first free row by id). By start time, two of them could swap rows.
        val events = eventRepository.withIcsIdentities(eventRepository.getAll().sortedBy { it.id })
        val ics = IcsCodec.encode(events.map { it.toIcsEvent() }, nowUtcMillis)
        return Result(ics = ics, eventCount = events.size)
    }

    data class Result(val ics: String, val eventCount: Int)
}
