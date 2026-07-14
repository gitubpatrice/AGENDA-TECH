package com.filestech.agenda_tech.data.device

import com.filestech.agenda_tech.core.text.BidiSanitizer
import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.model.Weekday
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pure mapper: a raw [DeviceEvent] (from the Calendar Provider) → a domain [Event]. No Android
 * dependency, so every branch (duration fallback, RRULE/EXDATE parsing, all-day, colour matching,
 * Bidi stripping) is unit-testable with plain values.
 *
 * Deliberately tolerant: a row that can't be mapped (blank title, unparseable start) returns null
 * and is skipped rather than aborting the whole import.
 */
object DeviceEventMapper {

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    private const val DEFAULT_DURATION_MILLIS = 60L * 60 * 1000 // 1h when nothing else is known

    // Untrusted third-party calendars could carry pathological field lengths / durations; bound both.
    private const val MAX_FIELD_CHARS = 4_000
    private const val MAX_DURATION_DAYS = 3_650L // ~10 years, per-component bound to avoid overflow
    private const val MAX_DURATION_MILLIS = MAX_DURATION_DAYS * DAY_MILLIS // anything longer is bogus

    private val BYDAY_TO_WEEKDAY = mapOf(
        "MO" to Weekday.MONDAY, "TU" to Weekday.TUESDAY, "WE" to Weekday.WEDNESDAY,
        "TH" to Weekday.THURSDAY, "FR" to Weekday.FRIDAY, "SA" to Weekday.SATURDAY, "SU" to Weekday.SUNDAY,
    )

    /** Compact iCalendar timestamps: `20250131T090000Z` (UTC) or `20250131T090000` (floating/local). */
    private val UTC_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val LOCAL_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun toEvent(device: DeviceEvent, calendarId: Long): Event? {
        val title = device.title?.let(::sanitize)?.trim().orEmpty()
        if (title.isEmpty()) return null

        val zoneId = device.eventTimeZone
            ?.let { tz -> runCatching { ZoneId.of(tz) }.getOrNull() }
            ?: ZoneId.systemDefault()

        val end = device.dtEndUtcMillis
            ?: device.durationRfc?.let { device.dtStartUtcMillis + parseDurationMillis(it) }
            ?: (device.dtStartUtcMillis + if (device.allDay) DAY_MILLIS else DEFAULT_DURATION_MILLIS)
        if (end < device.dtStartUtcMillis) return null

        return Event(
            calendarId = calendarId,
            title = title,
            description = device.description?.let(::sanitize)?.ifBlank { null },
            location = device.location?.let(::sanitize)?.ifBlank { null },
            startUtcMillis = device.dtStartUtcMillis,
            endUtcMillis = end,
            timeZoneId = zoneId.id,
            allDay = device.allDay,
            recurrence = device.rrule?.let { parseRRule(it, device.exDate, zoneId) },
        )
    }

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

    // Anti-Bidi spoofing (shared with the .ics path) + length cap against a hostile provider.
    private fun sanitize(text: String): String = BidiSanitizer.strip(text).take(MAX_FIELD_CHARS)

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
        val count = parts["COUNT"]?.toIntOrNull()
        val until = if (count == null) parts["UNTIL"]?.let { parseStamp(it, zone) } else null

        RecurrenceRule(
            freq = freq,
            interval = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            byWeekdays = parts["BYDAY"]
                ?.split(",")
                ?.mapNotNull { BYDAY_TO_WEEKDAY[it.trim().take(2).uppercase()] }
                ?.toSet()
                .orEmpty(),
            count = count?.takeIf { it >= 1 },
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

    /**
     * RFC 2445/5545 duration (e.g. `P1D`, `PT1H30M`, `PT3600S`) → milliseconds; 0 on failure. Each
     * component is bounded so a pathological value (`P999999999D`) can't overflow the Long cascade.
     */
    private fun parseDurationMillis(duration: String): Long {
        val m = DURATION_REGEX.matchEntire(duration.trim()) ?: return 0L
        val (weeks, days, hours, minutes, seconds) = m.destructured
        fun comp(token: String) = token.dropLast(1).toLongOrNull()?.coerceIn(0, MAX_DURATION_DAYS) ?: 0
        val w = comp(weeks); val d = comp(days); val h = comp(hours); val mi = comp(minutes); val se = comp(seconds)
        val millis = ((((w * 7 + d) * 24 + h) * 60 + mi) * 60 + se) * 1000
        return millis.coerceIn(0, MAX_DURATION_MILLIS)
    }

    // Optional sign is tolerated but ignored (durations here are always positive event lengths).
    private val DURATION_REGEX =
        Regex("[+-]?P(?:(\\d+W)|)(?:(\\d+D)|)(?:T(?:(\\d+H)|)(?:(\\d+M)|)(?:(\\d+S)|))?")
}
