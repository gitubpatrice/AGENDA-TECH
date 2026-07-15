package com.filestech.agenda_tech.domain.model

/**
 * A calendar already present on the device, owned by a system account (Google, Exchange, local…).
 * Purely local — reading it involves no network.
 */
data class DeviceCalendar(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val colorArgb: Int?,
)

/**
 * One event as the device's calendar store holds it, reduced to plain values so the mapping to
 * [Event] stays pure and unit-testable without a `Cursor`.
 *
 * Times follow the platform's calendar contract: [dtStartUtcMillis] is an absolute instant;
 * [dtEndUtcMillis] is null for recurring events (which carry [durationRfc] instead); all-day events
 * are anchored to UTC midnight by the provider.
 *
 * [uid] is a stable per-row identity used to update the same Agenda Tech row on re-import instead of
 * duplicating it; [deviceId] is the local row id, the fallback identity before the account syncs.
 * [originalId] / [originalInstanceTime] are set only on a moved occurrence and point at the master
 * series and the instant it replaces.
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
    val deviceId: Long,
    val originalId: Long?,
    val originalInstanceTime: Long?,
)
