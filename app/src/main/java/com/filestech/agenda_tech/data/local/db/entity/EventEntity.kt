package com.filestech.agenda_tech.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.domain.model.RecurrenceFreq

/**
 * Persisted event.
 *
 * Recurrence is stored as **structured columns** (`rrule_*`) rather than a serialised RFC 5545
 * string. This keeps the recurrence queryable in SQL and needs no RRULE parser to read/write the
 * DB — the RFC 5545 *text* form is only produced/consumed by the `.ics` interop layer (phase 2).
 * `rrule_freq == null` ⇒ single, non-recurring event; the other `rrule_*` columns are then inert.
 *
 * `rrule_by_weekdays` / `rrule_exdates` are comma-separated primitives (ISO weekday numbers,
 * epoch-millis) assembled into the domain [com.filestech.agenda_tech.domain.model.RecurrenceRule]
 * by the mapper — deliberately no Room `TypeConverter` for collections (keeps the DDL transparent).
 *
 * Deleting the owning calendar cascades to its events (FK ON DELETE CASCADE).
 */
@Entity(
    tableName = "events",
    foreignKeys = [
        ForeignKey(
            entity = CalendarEntity::class,
            parentColumns = ["id"],
            childColumns = ["calendar_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["calendar_id"]),
        Index(value = ["start_utc_millis"]),
        Index(value = ["end_utc_millis"]),
        Index(value = ["recurrence_parent_id"]),
        Index(value = ["calendar_id", "source_uid"]),
    ],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "calendar_id") val calendarId: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "description") val description: String?,
    @ColumnInfo(name = "location") val location: String?,
    // Postal address, kept in separate columns rather than crammed into `location`, which stays the
    // free label of the place. All nullable: an event rarely needs any of them.
    @ColumnInfo(name = "address") val address: String? = null,
    @ColumnInfo(name = "postal_code") val postalCode: String? = null,
    @ColumnInfo(name = "city") val city: String? = null,
    @ColumnInfo(name = "gps_coordinates") val gpsCoordinates: String? = null,
    @ColumnInfo(name = "start_utc_millis") val startUtcMillis: Long,
    @ColumnInfo(name = "end_utc_millis") val endUtcMillis: Long,
    @ColumnInfo(name = "time_zone") val timeZoneId: String,
    @ColumnInfo(name = "all_day") val allDay: Boolean,
    // --- Recurrence (rrule_freq null ⇒ non-recurring; other rrule_* columns then inert) ---
    @ColumnInfo(name = "rrule_freq") val rruleFreq: RecurrenceFreq?,
    @ColumnInfo(name = "rrule_interval") val rruleInterval: Int,
    @ColumnInfo(name = "rrule_by_weekdays") val rruleByWeekdays: String,
    @ColumnInfo(name = "rrule_count") val rruleCount: Int?,
    @ColumnInfo(name = "rrule_until") val rruleUntilUtcMillis: Long?,
    @ColumnInfo(name = "rrule_exdates") val rruleExDates: String,
    // --- Per-occurrence override (iCalendar RECURRENCE-ID); both null for masters/standalone ---
    @ColumnInfo(name = "recurrence_parent_id") val recurrenceParentId: Long?,
    @ColumnInfo(name = "original_start") val originalStartUtcMillis: Long?,
    // Stable source id (iCalendar UID) for idempotent re-import; null for user-created events.
    @ColumnInfo(name = "source_uid") val sourceUid: String? = null,
    // --- Appearance / audit ---
    @ColumnInfo(name = "color_override") val colorOverride: CalendarColor?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
