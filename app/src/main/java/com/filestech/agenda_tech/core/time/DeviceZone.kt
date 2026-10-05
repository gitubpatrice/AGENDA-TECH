package com.filestech.agenda_tech.core.time

import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneId

/**
 * The phone's time zone, as a flow: it changes while the app runs — a flight, or a change in the
 * system settings.
 *
 * The views read the zone once, when they were created. An open screen therefore kept the old zone
 * until the process ended: meetings drawn at the old zone's hours, "today" taken from the other side
 * of the world. Everything that lays out days or clock times now follows this one source, the views
 * through [zone] and the composables through `LocalDeviceZone`, which is fed from it.
 */
interface DeviceZone {
    val zone: StateFlow<ZoneId>
}
