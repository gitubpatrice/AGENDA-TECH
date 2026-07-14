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
 * One-shot import of events from the device's own calendars (Google, Exchange, local…) read via the
 * Calendar Provider — **no network**. Each selected device calendar becomes a *new* Agenda Tech
 * calendar (name + nearest palette colour), and its master/standalone events are copied in.
 *
 * V1 is a copy, not a sync: re-running it duplicates events (there is no dedup/two-way sync). Moved
 * single occurrences and VALARM reminders are out of scope, mirroring the `.ics` import.
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

    suspend operator fun invoke(selectedCalendarIds: List<Long>): Result = withContext(io) {
        val byId = reader.listCalendars().associateBy { it.id }
        var calendars = 0
        var events = 0
        for (deviceId in selectedCalendarIds.distinct()) {
            val deviceCal = byId[deviceId] ?: continue
            // Isolate each calendar: a failure on one (bad row, write error) is logged and skipped,
            // never crashes the whole import — mirrors the resilient .ics import path.
            runCatching {
                val targetCalendarId = calendarRepository.upsert(
                    Calendar(
                        name = deviceCal.displayName.ifBlank { deviceCal.accountName.ifBlank { "Import" } },
                        color = DeviceEventMapper.nearestColor(deviceCal.colorArgb),
                        isVisible = true,
                        isDefault = false,
                    ),
                )
                val mapped = reader.readEvents(deviceId)
                    .mapNotNull { DeviceEventMapper.toEvent(it, targetCalendarId) }
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
}
