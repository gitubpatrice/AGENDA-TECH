package com.filestech.agenda_tech.core.time

import kotlinx.coroutines.flow.MutableStateFlow
import java.time.ZoneId

/** A [DeviceZone] a test sets by hand — a journey is one assignment to [zone]. */
class FakeDeviceZone(initial: ZoneId = ZoneId.systemDefault()) : DeviceZone {
    override val zone = MutableStateFlow(initial)
}
