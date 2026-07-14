package com.filestech.agenda_tech.domain.ics

import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.core.text.BidiSanitizer
import com.filestech.agenda_tech.domain.model.Weekday
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pure RFC 5545 (`.ics`) codec for the subset Agenda Tech uses — VEVENT with DTSTART/DTEND,
 * SUMMARY/DESCRIPTION/LOCATION, RRULE (FREQ/INTERVAL/BYDAY/COUNT/UNTIL) and EXDATE. No Android;
 * exhaustively unit-testable, and lossless on its own round-trip (time zones preserved via the
 * `TZID` parameter).
 *
 * Deliberate scope limits (documented, safe): no VTIMEZONE block is emitted (the `TZID` name is
 * enough for our own round-trip and for well-known zones in other apps), and VALARM/reminders are
 * not exported. Import is tolerant: unknown properties are ignored, lines are unfolded, and both
 * UTC (`…Z`), zoned (`TZID=`) and floating date-times are accepted.
 */
object IcsCodec {

    private const val PRODID = "-//Files Tech//Agenda Tech//EN"
    private const val CRLF = "\r\n"
    private const val FOLD_LIMIT = 73

    private val UTC_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val LOCAL_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd")

    private val ISO_TO_BYDAY = mapOf(
        Weekday.MONDAY to "MO", Weekday.TUESDAY to "TU", Weekday.WEDNESDAY to "WE",
        Weekday.THURSDAY to "TH", Weekday.FRIDAY to "FR", Weekday.SATURDAY to "SA", Weekday.SUNDAY to "SU",
    )
    private val BYDAY_TO_ISO = ISO_TO_BYDAY.entries.associate { (k, v) -> v to k }

    // --- Encode --------------------------------------------------------------

    fun encode(events: List<IcsEvent>, nowUtcMillis: Long): String {
        val out = StringBuilder()
        out.appendContentLine("BEGIN:VCALENDAR")
        out.appendContentLine("VERSION:2.0")
        out.appendContentLine("PRODID:$PRODID")
        out.appendContentLine("CALSCALE:GREGORIAN")
        events.forEachIndexed { index, event -> appendVEvent(out, event, index, nowUtcMillis) }
        out.append("END:VCALENDAR").append(CRLF)
        return out.toString()
    }

    private fun StringBuilder.appendContentLine(line: String) {
        append(fold(line)).append(CRLF)
    }

    private fun appendVEvent(out: StringBuilder, event: IcsEvent, index: Int, nowUtcMillis: Long) {
        out.appendContentLine("BEGIN:VEVENT")
        out.appendContentLine("UID:agenda-tech-$index-${event.startUtcMillis}@filestech")
        out.appendContentLine("DTSTAMP:${utcStamp(nowUtcMillis)}")
        out.appendContentLine(dateProperty("DTSTART", event.startUtcMillis, event))
        out.appendContentLine(dateProperty("DTEND", event.endUtcMillis, event))
        out.appendContentLine("SUMMARY:${escapeText(event.title)}")
        event.description?.takeIf { it.isNotBlank() }?.let { out.appendContentLine("DESCRIPTION:${escapeText(it)}") }
        event.location?.takeIf { it.isNotBlank() }?.let { out.appendContentLine("LOCATION:${escapeText(it)}") }
        event.recurrence?.let { rule ->
            out.appendContentLine("RRULE:${encodeRRule(rule)}")
            if (rule.exDatesUtcMillis.isNotEmpty()) {
                out.appendContentLine("EXDATE:${rule.exDatesUtcMillis.joinToString(",") { utcStamp(it) }}")
            }
        }
        out.appendContentLine("END:VEVENT")
    }

    private fun dateProperty(name: String, utcMillis: Long, event: IcsEvent): String = when {
        event.allDay -> {
            val date = Instant.ofEpochMilli(utcMillis).atZone(zoneOf(event)).toLocalDate()
            "$name;VALUE=DATE:${date.format(DATE_STAMP)}"
        }
        event.timeZoneId == "UTC" -> "$name:${utcStamp(utcMillis)}"
        else -> {
            val local = Instant.ofEpochMilli(utcMillis).atZone(zoneOf(event)).toLocalDateTime()
            "$name;TZID=${event.timeZoneId}:${local.format(LOCAL_STAMP)}"
        }
    }

    private fun encodeRRule(rule: RecurrenceRule): String = buildString {
        append("FREQ=").append(rule.freq.name)
        if (rule.interval > 1) append(";INTERVAL=").append(rule.interval)
        if (rule.freq == RecurrenceFreq.WEEKLY && rule.byWeekdays.isNotEmpty()) {
            append(";BYDAY=").append(rule.byWeekdays.sortedBy { it.isoValue }.joinToString(",") { ISO_TO_BYDAY.getValue(it) })
        }
        rule.count?.let { append(";COUNT=").append(it) }
        rule.untilUtcMillis?.let { append(";UNTIL=").append(utcStamp(it)) }
    }

    private fun utcStamp(utcMillis: Long): String =
        Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).format(UTC_STAMP)

    private fun escapeText(text: String): String =
        text.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")

    /** Fold a content line to ≤ [FOLD_LIMIT] chars, continuation lines prefixed with a space. */
    private fun fold(line: String): String {
        if (line.length <= FOLD_LIMIT) return line
        val builder = StringBuilder()
        var index = 0
        while (index < line.length) {
            val end = minOf(index + FOLD_LIMIT, line.length)
            if (index > 0) builder.append(CRLF).append(' ')
            builder.append(line, index, end)
            index = end
        }
        return builder.toString()
    }

    // --- Decode --------------------------------------------------------------

    fun decode(text: String, defaultZone: ZoneId): List<IcsEvent> {
        val lines = unfold(text)
        val events = ArrayList<IcsEvent>()
        var current: MutableMap<String, IcsProperty>? = null
        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> current = LinkedHashMap()
                line == "END:VEVENT" -> {
                    current?.let { parseVEvent(it, defaultZone)?.let(events::add) }
                    current = null
                }
                current != null -> {
                    val property = parsePropertyLine(line) ?: continue
                    current[property.name] = property
                }
            }
        }
        return events
    }

    private fun parseVEvent(props: Map<String, IcsProperty>, defaultZone: ZoneId): IcsEvent? {
        // SEC-ICS3 — an event with no usable title is dropped (matches the editor's non-blank rule).
        val summary = props["SUMMARY"]?.value?.let(::unescapeText)?.let(::sanitizeText)
            ?.takeIf { it.isNotBlank() } ?: return null
        val dtStart = props["DTSTART"] ?: return null
        val start = parseDateTime(dtStart, defaultZone) ?: return null
        val dtEnd = props["DTEND"]
        val end = dtEnd?.let { parseDateTime(it, defaultZone) } ?: start
        val allDay = dtStart.params["VALUE"] == "DATE"
        val zoneId = when {
            allDay -> defaultZone.id
            dtStart.value.endsWith("Z") -> "UTC"
            else -> dtStart.params["TZID"] ?: defaultZone.id
        }
        val recurrence = props["RRULE"]?.let { parseRRule(it.value, props["EXDATE"], defaultZone) }
        return IcsEvent(
            title = summary,
            description = props["DESCRIPTION"]?.value?.let(::unescapeText)?.let(::sanitizeText),
            location = props["LOCATION"]?.value?.let(::unescapeText)?.let(::sanitizeText),
            startUtcMillis = start,
            endUtcMillis = maxOf(end, start),
            timeZoneId = zoneId,
            allDay = allDay,
            recurrence = recurrence,
        )
    }

    private fun parseDateTime(property: IcsProperty, defaultZone: ZoneId): Long? = runCatching {
        val raw = property.value.trim()
        when {
            property.params["VALUE"] == "DATE" || (raw.length == 8 && !raw.contains('T')) ->
                LocalDate.parse(raw, DATE_STAMP).atStartOfDay(defaultZone).toInstant().toEpochMilli()
            raw.endsWith("Z") ->
                LocalDateTime.parse(raw.dropLast(1), LOCAL_STAMP).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
            else -> {
                val zone = property.params["TZID"]?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: defaultZone
                LocalDateTime.parse(raw, LOCAL_STAMP).atZone(zone).toInstant().toEpochMilli()
            }
        }
    }.getOrNull()

    private fun parseRRule(value: String, exDate: IcsProperty?, defaultZone: ZoneId): RecurrenceRule? = runCatching {
        val parts = value.split(";").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0].uppercase() to kv[1] else null
        }.toMap()
        val freq = RecurrenceFreq.entries.firstOrNull { it.name == parts["FREQ"]?.uppercase() } ?: return null
        val exDates = exDate?.value?.split(",")?.mapNotNull { token ->
            parseDateTime(IcsProperty("EXDATE", exDate.params, token.trim()), defaultZone)
        }.orEmpty()
        RecurrenceRule(
            freq = freq,
            interval = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            byWeekdays = parts["BYDAY"]?.split(",")?.mapNotNull { BYDAY_TO_ISO[it.trim().uppercase()] }?.toSet().orEmpty(),
            count = parts["COUNT"]?.toIntOrNull(),
            untilUtcMillis = parts["UNTIL"]?.let { parseDateTime(IcsProperty("UNTIL", emptyMap(), it), defaultZone) },
        )
    }.getOrNull()

    private fun parsePropertyLine(line: String): IcsProperty? {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val headParts = head.split(";")
        val name = headParts.first().uppercase()
        val params = headParts.drop(1).mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0].uppercase() to kv[1] else null
        }.toMap()
        return IcsProperty(name, params, value)
    }

    /**
     * Join RFC 5545 folded lines (a following line starting with space/tab continues the previous).
     *
     * SEC-ICS1 — accumulates each logical line in a [StringBuilder] rather than repeatedly
     * concatenating immutable strings, so a maliciously deep fold stays O(n) instead of O(n²).
     */
    private fun unfold(text: String): List<String> {
        val rawLines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val result = ArrayList<String>()
        var current: StringBuilder? = null
        for (raw in rawLines) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && current != null) {
                current.append(raw, 1, raw.length)
            } else {
                current?.let { result += it.toString() }
                current = if (raw.isNotEmpty()) StringBuilder(raw) else null
            }
        }
        current?.let { result += it.toString() }
        return result
    }

    /**
     * SEC-ICS2 — strip Unicode bidirectional-control characters from imported free text and cap its
     * length. An imported `.ics` is untrusted; without the strip an RLO/LRO override could spoof how
     * a title reads on screen/in the widget, and without the cap a single multi-MB folded field
     * could bloat the DB (same guard as the device-calendar import).
     */
    private fun sanitizeText(text: String): String = BidiSanitizer.stripAndCap(text)

    private fun unescapeText(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    'n', 'N' -> out.append('\n')
                    ',' -> out.append(',')
                    ';' -> out.append(';')
                    '\\' -> out.append('\\')
                    else -> out.append(text[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun zoneOf(event: IcsEvent): ZoneId =
        runCatching { ZoneId.of(event.timeZoneId) }.getOrDefault(ZoneOffset.UTC)

    private data class IcsProperty(val name: String, val params: Map<String, String>, val value: String)
}
