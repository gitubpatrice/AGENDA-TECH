package com.filestech.agenda_tech.domain.device

import com.filestech.agenda_tech.core.text.BidiSanitizer
import com.filestech.agenda_tech.core.time.DAY_MILLIS
import com.filestech.agenda_tech.core.time.TimeZones
import com.filestech.agenda_tech.domain.ics.RfcDuration
import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.domain.model.DeviceEvent
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.model.Weekday
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pure mapper: a [DeviceEvent] read from the device calendar → a domain [Event]. No Android
 * dependency, so every branch (duration fallback, RRULE/EXDATE parsing, all-day, colour matching,
 * Bidi stripping) is unit-testable with plain values.
 *
 * Deliberately tolerant: a row that can't be mapped (blank title, unparseable start) returns null
 * and is skipped rather than aborting the whole import.
 */
object DeviceEventMapper {

    private const val DEFAULT_DURATION_MILLIS = 60L * 60 * 1000 // 1h when nothing else is known

    // Les bornes des durées pathologiques (un agenda tiers peut en porter) vivent desormais dans
    // RfcDuration, partage avec IcsCodec — un seul plafond, jamais une seconde copie du nombre
    // (audit AG-1). La longueur des champs, elle, reste plafonnee par BidiSanitizer.stripAndCap.

    private val BYDAY_TO_WEEKDAY = mapOf(
        "MO" to Weekday.MONDAY, "TU" to Weekday.TUESDAY, "WE" to Weekday.WEDNESDAY,
        "TH" to Weekday.THURSDAY, "FR" to Weekday.FRIDAY, "SA" to Weekday.SATURDAY, "SU" to Weekday.SUNDAY,
    )

    /** Compact iCalendar timestamps: `20250131T090000Z` (UTC) or `20250131T090000` (floating/local). */
    private val UTC_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val LOCAL_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun toEvent(device: DeviceEvent, calendarId: Long, defaultZone: ZoneId = ZoneId.systemDefault()): Event? {
        val title = device.title?.let(::sanitize)?.trim().orEmpty()
        if (title.isEmpty()) return null

        // Time model. All-day events are stored by the provider at UTC midnight; the rest of the app
        // anchors all-day to the *device* zone (local midnight, exclusive end). Re-anchor here so an
        // all-day holiday lands on its real calendar day instead of drifting by the UTC offset
        // (e.g. "Fête nationale" showing on the 15th instead of the 14th).
        val start: Long
        val end: Long
        val zoneId: ZoneId
        if (device.allDay) {
            val date = Instant.ofEpochMilli(device.dtStartUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
            val days = device.dtEndUtcMillis
                ?.let { ((it - device.dtStartUtcMillis) / DAY_MILLIS).coerceAtLeast(1) }
                ?: 1
            zoneId = defaultZone
            start = date.atStartOfDay(defaultZone).toInstant().toEpochMilli()
            end = date.plusDays(days).atStartOfDay(defaultZone).toInstant().toEpochMilli()
        } else {
            // Audit F3 — one resolver for every ingestion path. This one already validated the zone
            // (it was the only one that did), but with its own rules: a device row synced from
            // Exchange carries a Windows zone name, which it read as the device zone.
            zoneId = TimeZones.resolve(device.eventTimeZone, defaultZone)
            start = device.dtStartUtcMillis
            end = device.dtEndUtcMillis
                ?: device.durationRfc
                    ?.let(RfcDuration::parseMillis)
                    ?.takeIf { it > 0 }
                    ?.let { device.dtStartUtcMillis + it }
                ?: (device.dtStartUtcMillis + DEFAULT_DURATION_MILLIS)
            if (end < start) return null
        }

        return Event(
            calendarId = calendarId,
            title = title,
            description = device.description?.let(::sanitize)?.ifBlank { null },
            location = device.location?.let(::sanitize)?.ifBlank { null },
            startUtcMillis = start,
            endUtcMillis = end,
            timeZoneId = zoneId.id,
            allDay = device.allDay,
            recurrence = device.rrule?.let { parseRRule(it, device.exDate, zoneId) },
            sourceUid = device.uid,
        )
    }

    /**
     * Fallback uid for an event with no sync id (local calendar, or created while offline and not yet
     * pushed). The import matches on this form too, to catch the `rowid → sync-id` transition and
     * avoid re-inserting the event once it finally syncs.
     */
    fun rowIdUid(eventRowId: Long): String = "rowid:$eventRowId"

    /** Nearest colour in the closed palette to a device ARGB (Euclidean in RGB); DEFAULT if null. */
    fun nearestColor(argb: Int?): CalendarColor {
        if (argb == null) return CalendarColor.DEFAULT
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return CalendarColor.entries.minBy { c ->
            val cr = (c.argb shr 16) and 0xFF
            val cg = (c.argb shr 8) and 0xFF
            val cb = c.argb and 0xFF
            val dr = r - cr; val dg = g - cg; val db = b - cb
            dr * dr + dg * dg + db * db
        }
    }

    // Anti-Bidi spoofing + length cap against a hostile provider — the single shared guard, so the
    // two import paths (.ics and device) can never drift apart.
    private fun sanitize(text: String): String = BidiSanitizer.stripAndCap(text)

    /**
     * Parses an RFC 5545 `RRULE` (the subset the app models) into a [RecurrenceRule], folding in the
     * `EXDATE` column. Returns null on an unsupported/absent FREQ so the event imports as single.
     */
    private fun parseRRule(rrule: String, exDate: String?, zone: ZoneId): RecurrenceRule? = runCatching {
        val parts = rrule.substringAfter("RRULE:", rrule)
            .split(";")
            .mapNotNull { token ->
                val kv = token.split("=", limit = 2)
                if (kv.size == 2) kv[0].trim().uppercase() to kv[1].trim() else null
            }.toMap()

        val freq = RecurrenceFreq.entries.firstOrNull { it.name == parts["FREQ"]?.uppercase() } ?: return null
        // COUNT is filtered BEFORE deciding UNTIL: a COUNT of 0 is not a bound, so it must not
        // suppress a perfectly good UNTIL on its way to being discarded itself — that dropped both
        // bounds and silently turned a finite series infinite. Same order as IcsCodec.parseRRule.
        val count = parts["COUNT"]?.toIntOrNull()?.takeIf { it >= 1 }
        val until = if (count == null) parts["UNTIL"]?.let { parseStamp(it, zone) } else null

        RecurrenceRule(
            freq = freq,
            interval = parts["INTERVAL"]?.toIntOrNull()
                ?.coerceIn(1, RecurrenceRule.MAX_INTERVAL) ?: 1,
            byWeekdays = parts["BYDAY"]
                ?.split(",")
                ?.mapNotNull { BYDAY_TO_WEEKDAY[it.trim().take(2).uppercase()] }
                ?.toSet()
                .orEmpty(),
            count = count,
            untilUtcMillis = until,
            exDatesUtcMillis = parseExDates(exDate, zone),
        )
    }.getOrNull()

    private fun parseExDates(exDate: String?, zone: ZoneId): List<Long> {
        if (exDate.isNullOrBlank()) return emptyList()
        // The EXDATE column may carry a `TZID=...:` prefix before a comma-separated stamp list.
        val payload = exDate.substringAfterLast(':')
        return payload.split(",")
            .mapNotNull { runCatching { parseStamp(it.trim(), zone) }.getOrNull() }
    }

    /** Parses a compact iCalendar date/date-time (`…Z`, floating, or date-only) to epoch-millis. */
    private fun parseStamp(raw: String, zone: ZoneId): Long? {
        val s = raw.trim()
        return when {
            s.isEmpty() -> null
            s.endsWith("Z") ->
                runCatching { LocalDateTime.parse(s, UTC_STAMP).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
            s.contains("T") ->
                runCatching { LocalDateTime.parse(s, LOCAL_STAMP).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
            else ->
                runCatching { LocalDate.parse(s, DATE_STAMP).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull()
        }
    }
}
