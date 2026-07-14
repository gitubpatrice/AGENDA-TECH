package com.filestech.agenda_tech.domain.model

/**
 * A reminder that fires [minutesBefore] the event start. An event may have several (e.g. 1 day
 * and 10 minutes before). The actual scheduling against the OS exact-alarm subsystem is a phase-2
 * concern ([com.filestech.agenda_tech.domain.repository.ReminderRepository] only persists intent).
 *
 * `id == 0L` denotes an unsaved reminder.
 */
data class Reminder(
    val id: Long = 0L,
    val eventId: Long,
    val minutesBefore: Int,
) {
    init {
        require(minutesBefore >= 0) { "Reminder minutesBefore must be >= 0, was $minutesBefore" }
    }
}
