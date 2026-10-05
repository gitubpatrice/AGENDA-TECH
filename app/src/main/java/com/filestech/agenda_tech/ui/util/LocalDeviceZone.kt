package com.filestech.agenda_tech.ui.util

import androidx.compose.runtime.staticCompositionLocalOf
import java.time.ZoneId

/**
 * The phone's time zone for the composables that format clock times or find today, provided by
 * `MainActivity` from [com.filestech.agenda_tech.core.time.DeviceZone] — the source the view models
 * follow too, so a screen and its state never disagree on the zone. Static: it changes on a journey,
 * not per frame, and a change redraws everything that shows a time anyway.
 */
val LocalDeviceZone = staticCompositionLocalOf<ZoneId> { ZoneId.systemDefault() }
