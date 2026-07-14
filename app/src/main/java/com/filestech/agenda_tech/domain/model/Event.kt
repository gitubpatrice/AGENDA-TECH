package com.filestech.agenda_tech.domain.model

/**
 * A calendar event.
 *
 * Time model (identical to Android's `CalendarContract`): [startUtcMillis] / [endUtcMillis] are
 * absolute instants (epoch-millis, UTC) and [timeZoneId] is the IANA zone id (e.g. `Europe/Paris`)
 * the event was authored in. Storing both lets the UI render "9:00 in Paris" correctly and keeps
 * the instant stable across DST — the recurring-expansion phase re-anchors each occurrence to
 * [timeZoneId], never to a fixed offset.
 *
 * For [allDay] events the instants are the day boundaries in [timeZoneId] and the clock time is
 * not shown.
 *
 * [recurrence] null ⇒ single occurrence. [colorOverride] null ⇒ inherit the owning calendar's
 * colour. Reminders are modelled separately ([Reminder]) so an event can carry several.
 *
 * `id == 0L` denotes an unsaved event.
 */
data class Event(
    val id: Long = 0L,
    val calendarId: Long,
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
    val timeZoneId: String,
    val allDay: Boolean = false,
    val recurrence: RecurrenceRule? = null,
    val colorOverride: CalendarColor? = null,
) {
    init {
        require(endUtcMillis >= startUtcMillis) {
            "Event end ($endUtcMillis) must be >= start ($startUtcMillis)"
        }
    }

    val isRecurring: Boolean get() = recurrence != null
}
