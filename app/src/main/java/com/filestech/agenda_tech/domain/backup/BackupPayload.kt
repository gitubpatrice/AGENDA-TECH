package com.filestech.agenda_tech.domain.backup

import kotlinx.serialization.Serializable

/**
 * Everything a restore needs to rebuild the agenda, exactly as it was.
 *
 * Deliberately its own schema rather than a dump of the Room entities: the DB layout is an internal
 * detail that changes with migrations, whereas a backup file must still open in a year. Fields are
 * therefore explicit and defaulted — an older file simply lacks the newer ones and still restores.
 *
 * This is why `.ics` is not a backup: it loses reminders, colours, the address, the GPS point and the
 * calendar structure. It is an interchange format, not a safety net.
 *
 * [FORMAT_VERSION] is bumped only on a breaking change; a reader must refuse a version it does not
 * know rather than guess.
 */
@Serializable
data class BackupPayload(
    val formatVersion: Int = FORMAT_VERSION,
    /** Wall-clock of the export, purely informational (shown to the user before restoring). */
    val exportedAtUtcMillis: Long,
    val calendars: List<BackupCalendar>,
    val events: List<BackupEvent>,
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}

@Serializable
data class BackupCalendar(
    /** Kept so events can point back to their calendar within the file; not the restored row id. */
    val id: Long,
    val name: String,
    val colorRaw: Int,
    val isVisible: Boolean,
    val isDefault: Boolean,
    /** Link to the device calendar it was imported from, so a re-import stays idempotent after restore. */
    val sourceId: String? = null,
)

@Serializable
data class BackupEvent(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val address: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    val gpsCoordinates: String? = null,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
    val timeZoneId: String,
    val allDay: Boolean = false,
    val colorOverrideRaw: Int? = null,
    val sourceUid: String? = null,
    /** Recurrence, flattened — null freq means a single event. */
    val rruleFreqRaw: Int? = null,
    val rruleInterval: Int = 1,
    val rruleByWeekdaysIso: List<Int> = emptyList(),
    val rruleCount: Int? = null,
    val rruleUntilUtcMillis: Long? = null,
    val rruleExDatesUtcMillis: List<Long> = emptyList(),
    /** Per-occurrence override link (iCalendar RECURRENCE-ID model). */
    val recurrenceParentId: Long? = null,
    val originalStartUtcMillis: Long? = null,
    /** Minutes-before of each reminder — the whole point of a backup vs. an `.ics` export. */
    val reminderMinutes: List<Int> = emptyList(),
)
