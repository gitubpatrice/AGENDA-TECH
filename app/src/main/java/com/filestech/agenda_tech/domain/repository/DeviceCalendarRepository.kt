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
     * Deleted tombstones are excluded. Bounded to a sane maximum by the implementation.
     */
    suspend fun readEvents(deviceCalendarId: Long): List<DeviceEvent>
}
