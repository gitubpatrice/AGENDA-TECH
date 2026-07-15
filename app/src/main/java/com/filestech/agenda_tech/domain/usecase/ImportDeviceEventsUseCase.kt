package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.domain.device.DeviceEventMapper
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.DeviceCalendar
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.repository.DeviceCalendarRepository
import com.filestech.agenda_tech.domain.repository.EventRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

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
@Singleton
class ImportDeviceEventsUseCase @Inject constructor(
    private val deviceCalendars: DeviceCalendarRepository,
    private val calendarRepository: CalendarRepository,
    private val eventRepository: EventRepository,
) {
    /**
     * [failedCalendars] > 0 means some selected calendars could not be imported (read or write
     * error); the screen surfaces it instead of silently reporting "0 events".
     */
    data class Result(val calendars: Int, val events: Int, val failedCalendars: Int = 0)

    /**
     * Serialises imports: the get-or-create of a calendar by `source_id` is a read-then-write, so two
     * concurrent runs could otherwise each miss the other's insert and duplicate it (audit U3).
     */
    private val mutex = Mutex()

    /** Lists the device calendars available to import from (requires READ_CALENDAR granted). */
    suspend fun listCalendars(): List<DeviceCalendar> = deviceCalendars.listCalendars()

    /**
     * Wipes the calendars created by a previous import so a fresh import starts clean. Calendars the
     * user made by hand are never touched (see [CalendarRepository.deleteImported]).
     */
    suspend fun clearImported() = mutex.withLock { calendarRepository.deleteImported() }

    /**
     * Threading is the repositories' job (they each dispatch to IO), as in every other use case.
     *
     * [fallbackCalendarName] names a device calendar that reports neither a display name nor an
     * account — passed in (localised) because the domain has no access to string resources.
     */
    suspend operator fun invoke(selectedCalendarIds: List<Long>, fallbackCalendarName: String): Result =
        mutex.withLock { importLocked(selectedCalendarIds, fallbackCalendarName) }

    private suspend fun importLocked(selectedCalendarIds: List<Long>, fallbackCalendarName: String): Result {
        val byId = deviceCalendars.listCalendars().associateBy { it.id }
        var calendars = 0
        var events = 0
        var failed = 0
        for (deviceId in selectedCalendarIds.distinct()) {
            val deviceCal = byId[deviceId]
            if (deviceCal == null) {
                failed++
                continue
            }
            // Isolate each calendar: a failure on one (bad row, write error) is logged and skipped,
            // never crashes the whole import — mirrors the resilient .ics import path.
            runCatching {
                val sourceId = "$SOURCE_PREFIX$deviceId"
                // Reuse the calendar from a previous import (idempotent) or create it on first run.
                val targetCalendarId = calendarRepository.findBySourceId(sourceId)?.id
                    ?: calendarRepository.upsert(
                        Calendar(
                            name = deviceCal.displayName
                                .ifBlank { deviceCal.accountName }
                                .ifBlank { fallbackCalendarName },
                            color = DeviceEventMapper.nearestColor(deviceCal.colorArgb),
                            isVisible = true,
                            isDefault = false,
                            sourceId = sourceId,
                        ),
                    )
                val deviceEvents = deviceCalendars.readEvents(deviceId)
                // FIAB-3 — fold each moved occurrence's original instant into its master's EXDATE, so
                // a provider that omits EXDATE on the master can't leave a ghost at the original time
                // next to the moved one.
                val exByMasterDeviceId: Map<Long, List<Long>> = deviceEvents
                    .filter { it.originalId != null && it.originalInstanceTime != null }
                    .groupBy({ it.originalId!! }, { it.originalInstanceTime!! })
                // Map source uid → existing row id so a re-import updates in place instead of duplicating.
                val existing = eventRepository.sourceUidMap(targetCalendarId)
                val mapped = deviceEvents.mapNotNull { de ->
                    val ev = DeviceEventMapper.toEvent(de, targetCalendarId) ?: return@mapNotNull null
                    val rule = ev.recurrence
                    val withEx = if (rule != null) {
                        val extra = exByMasterDeviceId[de.deviceId].orEmpty()
                        if (extra.isEmpty()) ev
                        else ev.copy(recurrence = rule.copy(exDatesUtcMillis = (rule.exDatesUtcMillis + extra).distinct()))
                    } else {
                        ev
                    }
                    // Match the already-imported row by source uid, falling back to the local row-id
                    // form: an event first imported before its account synced was stored under
                    // `rowid:<id>`, and now carries a real `_SYNC_ID` — without this second key it
                    // would be re-inserted as a duplicate (audit U1).
                    val existingId = withEx.sourceUid?.let { existing[it] }
                        ?: existing[DeviceEventMapper.rowIdUid(de.deviceId)]
                    if (existingId != null) withEx.copy(id = existingId) else withEx
                }
                eventRepository.upsertAll(mapped) // atomic batch — all rows or none
                mapped.size
            }.onSuccess { imported ->
                calendars++
                events += imported
            }.onFailure {
                failed++
                Timber.w(it, "ImportDeviceEventsUseCase: calendar %d skipped", deviceId)
            }
        }
        return Result(calendars = calendars, events = events, failedCalendars = failed)
    }

    private companion object {
        const val SOURCE_PREFIX = "device:"
    }
}
