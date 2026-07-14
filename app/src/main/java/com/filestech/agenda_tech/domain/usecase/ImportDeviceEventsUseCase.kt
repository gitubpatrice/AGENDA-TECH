package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.data.device.DeviceCalendar
import com.filestech.agenda_tech.data.device.DeviceCalendarReader
import com.filestech.agenda_tech.data.device.DeviceEventMapper
import com.filestech.agenda_tech.di.IoDispatcher
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.repository.EventRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Import of events from the device's own calendars (Google, Exchange, local…) read via the Calendar
 * Provider — **no network**. Each selected device calendar maps to an Agenda Tech calendar (reused
 * on re-import via its `source_id`, name + nearest palette colour), and its events are copied in.
 *
 * **Idempotent**: each event carries the source's stable uid, so re-running the import updates the
 * same rows in place and adds newly-created events instead of duplicating everything — re-importing
 * is a safe refresh. It does not delete events removed at the source (no destructive two-way sync),
 * and VALARM reminders are out of scope (mirrors the `.ics` import).
 */
class ImportDeviceEventsUseCase @Inject constructor(
    private val reader: DeviceCalendarReader,
    private val calendarRepository: CalendarRepository,
    private val eventRepository: EventRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    data class Result(val calendars: Int, val events: Int)

    /** Lists the device calendars available to import from (requires READ_CALENDAR granted). */
    suspend fun listCalendars(): List<DeviceCalendar> = withContext(io) { reader.listCalendars() }

    /** Wipes previously imported calendars (incl. legacy ones) so a fresh import starts clean. */
    suspend fun clearImported() = calendarRepository.deleteImported()

    suspend operator fun invoke(selectedCalendarIds: List<Long>): Result = withContext(io) {
        val byId = reader.listCalendars().associateBy { it.id }
        var calendars = 0
        var events = 0
        for (deviceId in selectedCalendarIds.distinct()) {
            val deviceCal = byId[deviceId] ?: continue
            // Isolate each calendar: a failure on one (bad row, write error) is logged and skipped,
            // never crashes the whole import — mirrors the resilient .ics import path.
            runCatching {
                val sourceId = "$SOURCE_PREFIX$deviceId"
                // Reuse the calendar from a previous import (idempotent) or create it on first run.
                val targetCalendarId = calendarRepository.findBySourceId(sourceId)?.id
                    ?: calendarRepository.upsert(
                        Calendar(
                            name = deviceCal.displayName.ifBlank { deviceCal.accountName.ifBlank { "Import" } },
                            color = DeviceEventMapper.nearestColor(deviceCal.colorArgb),
                            isVisible = true,
                            isDefault = false,
                            sourceId = sourceId,
                        ),
                    )
                // Map source uid → existing row id so a re-import updates in place instead of duplicating.
                val existing = eventRepository.sourceUidMap(targetCalendarId)
                val mapped = reader.readEvents(deviceId)
                    .mapNotNull { DeviceEventMapper.toEvent(it, targetCalendarId) }
                    .map { ev ->
                        val existingId = ev.sourceUid?.let { existing[it] }
                        if (existingId != null) ev.copy(id = existingId) else ev
                    }
                eventRepository.upsertAll(mapped) // atomic batch — all rows or none
                mapped.size
            }.onSuccess { imported ->
                calendars++
                events += imported
            }.onFailure {
                Timber.w(it, "ImportDeviceEventsUseCase: calendar %d skipped", deviceId)
            }
        }
        Result(calendars = calendars, events = events)
    }

    private companion object {
        const val SOURCE_PREFIX = "device:"
    }
}
