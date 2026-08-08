package com.filestech.agenda_tech.domain.repository

import com.filestech.agenda_tech.domain.model.DeviceCalendar
import com.filestech.agenda_tech.domain.model.DeviceEvent

/**
 * Read-only access to the calendars the device already holds (Google, Exchange, local…).
 *
 * Behind this interface sits the platform's calendar store; keeping it here lets the import use case
 * stay pure domain code — no Android type, mockable in a plain unit test — like every other
 * repository. Implementations run off the main thread and require the calendar read permission,
 * which the caller gates on. **No network is ever involved**: this only reads what the system has
 * already synced onto the device.
 */
interface DeviceCalendarRepository {

    /** The device calendars available to import from. Empty on permission/query failure. */
    suspend fun listCalendars(): List<DeviceCalendar>

    /**
     * The events of a device calendar: recurring masters, standalone events and moved occurrences.
     * Deleted tombstones are excluded. At most [limit] are returned.
     *
     * The **caller** sets the limit rather than the implementation, and gets told whether it bit
     * (audit DR-9): the ceiling has to bound one import, and an import can select several calendars.
     * Applied per calendar, ten selected calendars imported ten times the announced maximum.
     */
    suspend fun readEvents(deviceCalendarId: Long, limit: Int): DeviceRead
}

/**
 * What one calendar yielded, and whether that was all of it.
 *
 * Audit SEC-6 — the truncation used to be a `Timber.w`, and `NoOpReleaseTree.log` is an empty method:
 * on the builds users run, a calendar of 25 000 events silently became 20 000 and nothing said so.
 * A fact the user needs has to be **returned**, not logged; a log is for the developer, and this one
 * did not even reach them.
 */
data class DeviceRead(val events: List<DeviceEvent>, val truncated: Boolean)
