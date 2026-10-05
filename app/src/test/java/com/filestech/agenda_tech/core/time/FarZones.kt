package com.filestech.agenda_tech.core.time

/**
 * Two zones 25 hours apart, neither with daylight saving. Whatever zone the machine running the tests is
 * set to, at least one of them has its midnight ten hours or more from the machine's. An all-day event
 * made in either straddles two dates on the machine's clock, on a different side for each — the day
 * before for a zone ahead, the day after for one behind — so a test of it runs on both.
 */
object FarZones {
    const val AHEAD = "Pacific/Kiritimati" // UTC+14
    const val BEHIND = "Pacific/Pago_Pago" // UTC−11
}
