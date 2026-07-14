package com.filestech.agenda_tech.data.device

/**
 * A calendar already present on the device (read from `CalendarContract.Calendars`). Owned by a
 * system account (Google, Exchange, local…). Purely local — reading it involves no network.
 */
data class DeviceCalendar(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val colorArgb: Int?,
)

/**
 * A raw event row read from `CalendarContract.Events`, kept as plain values so the
 * [DeviceEventMapper] (which turns it into a domain `Event`) stays pure and unit-testable without a
 * `Cursor`. Only master / standalone events are read (exception instances are skipped in V1).
 *
 * Time fields follow the Calendar Provider contract: [dtStartUtcMillis] is an absolute instant;
 * [dtEndUtcMillis] is null for recurring events (which carry [durationRfc] instead); [allDay] events
 * are anchored to UTC midnight by the provider.
 */
data class DeviceEvent(
    val title: String?,
    val description: String?,
    val location: String?,
    val dtStartUtcMillis: Long,
    val dtEndUtcMillis: Long?,
    val durationRfc: String?,
    val eventTimeZone: String?,
    val allDay: Boolean,
    val rrule: String?,
    val exDate: String?,
)
