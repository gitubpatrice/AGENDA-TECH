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
 * `Cursor`. Recurring masters, standalone events and moved single occurrences are all read; only
 * deleted tombstones are skipped.
 *
 * Time fields follow the Calendar Provider contract: [dtStartUtcMillis] is an absolute instant;
 * [dtEndUtcMillis] is null for recurring events (which carry [durationRfc] instead); [allDay] events
 * are anchored to UTC midnight by the provider.
 *
 * [uid] is a stable identifier of the source event (iCalendar UID / sync id / row id) used to update
 * the same Agenda Tech row on re-import instead of duplicating it.
 *
 * For a *moved single occurrence* of a recurring series the provider sets [originalId] (the master's
 * row id) and [originalInstanceTime] (the instant of the occurrence it replaces). The import folds
 * that instant into the master's EXDATE so the original occurrence doesn't survive as a ghost
 * alongside the moved one (FIAB-3). [deviceId] is the row's own `_ID`, used to correlate them.
 */
data class DeviceEvent(
    val uid: String,
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
    val deviceId: Long = 0L,
    val originalId: Long? = null,
    val originalInstanceTime: Long? = null,
)
