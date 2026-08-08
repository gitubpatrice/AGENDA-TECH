package com.filestech.agenda_tech.domain.backup

import com.filestech.agenda_tech.core.text.BidiSanitizer
import com.filestech.agenda_tech.core.time.TimeZones
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.model.Weekday
import kotlinx.serialization.json.Json
import java.time.ZoneId

/**
 * Pure translation between the domain models and the on-disk [BackupPayload], plus its JSON form.
 *
 * Kept free of Android and of any I/O so the whole round-trip is unit-testable: a backup that
 * silently drops a field is worse than no backup, because it is only discovered the day it is
 * restored.
 */
object BackupCodec {

    private val json = Json {
        // An older app must survive a file written by a newer one that added fields: skip what we
        // don't know rather than refuse the whole restore. Breaking changes are gated by
        // BackupPayload.FORMAT_VERSION instead.
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeToJson(payload: BackupPayload): String =
        json.encodeToString(BackupPayload.serializer(), payload)

    /** Throws on malformed JSON or an unsupported format version — callers map it to an [AppError]. */
    fun decodeFromJson(text: String): BackupPayload {
        val payload = json.decodeFromString(BackupPayload.serializer(), text)
        require(payload.formatVersion in 1..BackupPayload.FORMAT_VERSION) {
            "Unsupported backup format version ${payload.formatVersion}"
        }
        return payload
    }

    fun toPayload(
        calendars: List<Calendar>,
        events: List<Event>,
        remindersByEventId: Map<Long, List<Int>>,
        exportedAtUtcMillis: Long,
    ): BackupPayload = BackupPayload(
        exportedAtUtcMillis = exportedAtUtcMillis,
        calendars = calendars.map { it.toBackup() },
        events = events.map { it.toBackup(remindersByEventId[it.id].orEmpty()) },
    )

    private fun Calendar.toBackup() = BackupCalendar(
        id = id,
        name = name,
        colorRaw = color.rawValue,
        isVisible = isVisible,
        isDefault = isDefault,
        sourceId = sourceId,
    )

    private fun Event.toBackup(reminderMinutes: List<Int>) = BackupEvent(
        id = id,
        calendarId = calendarId,
        title = title,
        description = description,
        location = location,
        address = address,
        postalCode = postalCode,
        city = city,
        gpsCoordinates = gpsCoordinates,
        startUtcMillis = startUtcMillis,
        endUtcMillis = endUtcMillis,
        timeZoneId = timeZoneId,
        allDay = allDay,
        colorOverrideRaw = colorOverride?.rawValue,
        sourceUid = sourceUid,
        rruleFreqRaw = recurrence?.freq?.rawValue,
        rruleInterval = recurrence?.interval ?: 1,
        rruleByWeekdaysIso = recurrence?.byWeekdays?.map { it.isoValue }?.sorted().orEmpty(),
        rruleCount = recurrence?.count,
        rruleUntilUtcMillis = recurrence?.untilUtcMillis,
        rruleExDatesUtcMillis = recurrence?.exDatesUtcMillis.orEmpty(),
        recurrenceParentId = recurrenceParentId,
        originalStartUtcMillis = originalStartUtcMillis,
        reminderMinutes = reminderMinutes,
    )

    fun BackupCalendar.toDomain(): Calendar = Calendar(
        id = id,
        name = BidiSanitizer.stripAndCap(name),
        color = CalendarColor.fromRaw(colorRaw),
        isVisible = isVisible,
        isDefault = isDefault,
        sourceId = sourceId,
    )

    /**
     * Rebuilds the domain event. Throws when the file violates a domain invariant (e.g. end before
     * start) — a truncated or tampered file must abort the restore loudly, never land half-valid rows.
     */
    fun BackupEvent.toDomain(): Event = Event(
        id = id,
        calendarId = calendarId,
        // Audit S7 — the `.atbak` was the ONE ingestion path that treated its text as trustworthy.
        // `IcsCodec` and `DeviceEventMapper` both run every free-text field through BidiSanitizer;
        // this one ran none. It is not a lesser input: the KDoc of `IcsCodec.dateProperty` already
        // states that a hand-made .atbak carries its fields verbatim, which is exactly why the zone
        // below is normalised. The same file, the same attacker, the six fields next to it.
        //
        // Concretely: a shared backup ("here's my agenda, password xxx") whose title holds a U+202E
        // reverses how that title reads in the month view, the timeline, the HOME-SCREEN WIDGET and
        // the reminder notification — the three surfaces SECURITY.md is careful about elsewhere. And
        // the file cap is 16 MiB, so a multi-megabyte title is storable and then measured whole by
        // every Compose Text that renders it.
        //
        // Nothing legitimate is lost: a real agenda has no bidi controls and no 4000-character title.
        title = BidiSanitizer.stripAndCap(title),
        description = description?.let(BidiSanitizer::stripAndCap),
        location = location?.let(BidiSanitizer::stripAndCap),
        address = address?.let(BidiSanitizer::stripAndCap),
        postalCode = postalCode?.let(BidiSanitizer::stripAndCap),
        city = city?.let(BidiSanitizer::stripAndCap),
        gpsCoordinates = gpsCoordinates,
        startUtcMillis = startUtcMillis,
        endUtcMillis = endUtcMillis,
        // Audit F3 — the fourth ingestion path, and the only one that did not look at this field at
        // all. Repaired rather than rejected, for the same reason `interval` is clamped just below:
        // an .atbak written by an affected build is the user's own legitimate backup, and refusing it
        // would punish them for a defect of ours. The clamp also closes the injection route — a
        // hand-edited file could put a CRLF here, and this value reaches the .ics exporter.
        timeZoneId = TimeZones.normalize(timeZoneId, ZoneId.systemDefault()),
        allDay = allDay,
        recurrence = rruleFreqRaw?.let { freqRaw ->
            RecurrenceRule(
                freq = RecurrenceFreq.fromRaw(freqRaw),
                // Audit F1 — same clamp as the DB read: a .atbak taken by an affected build can carry
                // an out-of-range interval, and it is the user's own legitimate backup. Without this
                // the new require() in RecurrenceRule.init rejects the whole restore as unreadable.
                interval = rruleInterval.coerceIn(1, RecurrenceRule.MAX_INTERVAL),
                byWeekdays = rruleByWeekdaysIso.map { Weekday.fromIso(it) }.toSet(),
                count = rruleCount,
                untilUtcMillis = rruleUntilUtcMillis,
                exDatesUtcMillis = rruleExDatesUtcMillis,
            )
        },
        colorOverride = colorOverrideRaw?.let { CalendarColor.fromRaw(it) },
        recurrenceParentId = recurrenceParentId,
        originalStartUtcMillis = originalStartUtcMillis,
        sourceUid = sourceUid,
    )
}
