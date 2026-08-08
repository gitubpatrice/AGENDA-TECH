package com.filestech.agenda_tech.domain.ics

import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.core.text.BidiSanitizer
import com.filestech.agenda_tech.core.time.TimeZones
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

    /**
     * RFC 5545 §3.1 bounds a content line to 75 **octets** excluding the line break, and the space
     * that opens a continuation line counts toward its own 75. 73 leaves room for that space and a
     * margin, and it is what this codec has always emitted for ASCII — the unit here changed from
     * characters to octets (audit F14), not the width.
     */
    private const val FOLD_LIMIT_OCTETS = 73

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
        // Stable UID (FIAB-NEW-2): from the event's identity, not its position — so re-exporting an
        // evolving agenda keeps each event's UID and a later re-import updates instead of duplicating.
        // Only add the "@filestech" domain when the UID has none, so it never accumulates on round-trips.
        val base = event.uid?.takeIf { it.isNotBlank() } ?: "agenda-tech-$index-${event.startUtcMillis}"
        val uid = if (base.contains("@")) base else "$base@filestech"
        // Audit F2/F4 - the UID is attacker-controlled on any imported event and was the one TEXT
        // value written raw: real newlines in it became content lines, so an exported .ics could
        // carry whole VEVENTs the attacker wrote, under the user's name.
        out.appendContentLine("UID:${escapeText(uid)}")
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

    /**
     * Audit F3c — the `TZID` parameter is written from the **resolved** zone's id, never from the
     * stored string. The stored string is attacker-reachable (a hand-made `.atbak` carries the field
     * verbatim) and was the one place a value went into the output without passing through either
     * [escapeText] or a validator: a zone id holding a CRLF ended the content line and turned the rest
     * into properties of the attacker's choosing, inside a file the user then shares. A [ZoneId] id
     * cannot contain one. This also removes the last way the emitted offset and the emitted zone name
     * could disagree, since both now come from the same [ZoneId].
     */
    private fun dateProperty(name: String, utcMillis: Long, event: IcsEvent): String {
        val zone = zoneOf(event)
        return when {
            event.allDay -> {
                val date = Instant.ofEpochMilli(utcMillis).atZone(zone).toLocalDate()
                "$name;VALUE=DATE:${date.format(DATE_STAMP)}"
            }
            zone.id == "UTC" -> "$name:${utcStamp(utcMillis)}"
            else -> {
                val local = Instant.ofEpochMilli(utcMillis).atZone(zone).toLocalDateTime()
                "$name;TZID=${zone.id}:${local.format(LOCAL_STAMP)}"
            }
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

    /**
     * RFC 5545 TEXT escaping.
     *
     * Audit F9/F10/F11 - a carriage return has to be neutralised too. Escaping only LF left a bare
     * CR in the output, which every unfolder treats as a line break: a CR in a title (pasted text,
     * a device-imported event) silently split the content line and turned the remainder into a
     * forged property. CRLF is collapsed first so a real line break becomes one escape, not two.
     */
    private fun escapeText(text: String): String =
        text.replace("\\", "\\\\")
            .replace("\r\n", "\\n")
            .replace("\r", "\\n")
            .replace("\n", "\\n")
            .replace(",", "\\,")
            .replace(";", "\\;")

    /**
     * Fold a content line to ≤ [FOLD_LIMIT_OCTETS] octets, continuation lines prefixed with a space.
     *
     * Audit F14 — this counted Kotlin `Char`s, which are UTF-16 code *units*. An emoji is two of them
     * (a surrogate pair), so a fold landing between the two split the pair; the halves are not
     * characters, and `toByteArray(UTF_8)` maps an unpaired surrogate to `?`. An emoji at the wrong
     * offset in a title therefore came out of the exporter as `??` — silent, and unrecoverable from
     * the file. Counting octets and stepping by whole code points is also what RFC 5545 asks for: it
     * bounds the line in octets and forbids folding inside a multi-octet character.
     */
    private fun fold(line: String): String {
        val builder = StringBuilder(line.length + line.length / FOLD_LIMIT_OCTETS + 1)
        var octets = 0
        var index = 0
        while (index < line.length) {
            val codePoint = line.codePointAt(index)
            val chars = Character.charCount(codePoint)
            val width = utf8Length(codePoint)
            if (octets > 0 && octets + width > FOLD_LIMIT_OCTETS) {
                builder.append(CRLF).append(' ')
                octets = 0
            }
            builder.append(line, index, index + chars)
            octets += width
            index += chars
        }
        return builder.toString()
    }

    /** Octets [codePoint] occupies in UTF-8. */
    private fun utf8Length(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

    // --- Decode --------------------------------------------------------------

    /**
     * Audit F8 — properties are collected into a **list per name**, not a single slot.
     *
     * `EXDATE` is one of the few RFC 5545 properties allowed to appear several times in a VEVENT, and
     * every producer that cancels more than a handful of occurrences uses that form rather than one
     * long comma-separated line. Keeping only the last one silently resurrected every occurrence the
     * user had cancelled — including on a round-trip through a file this app had itself imported.
     *
     * For the properties RFC 5545 allows only once, the **first** wins: a malformed file that repeats
     * SUMMARY does not get to have its later copy override the one a reader would show.
     */
    fun decode(text: String, defaultZone: ZoneId): List<IcsEvent> {
        val lines = unfold(text)
        val events = ArrayList<IcsEvent>()
        var current: MutableMap<String, MutableList<IcsProperty>>? = null
        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> current = LinkedHashMap()
                line == "END:VEVENT" -> {
                    current?.let { parseVEvent(it, defaultZone)?.let(events::add) }
                    current = null
                }
                current != null -> {
                    val property = parsePropertyLine(line) ?: continue
                    current.getOrPut(property.name) { ArrayList() } += property
                }
            }
        }
        return events
    }

    private fun Map<String, List<IcsProperty>>.first(name: String): IcsProperty? = this[name]?.firstOrNull()

    private fun parseVEvent(props: Map<String, List<IcsProperty>>, defaultZone: ZoneId): IcsEvent? {
        // SEC-ICS3 — an event with no usable title is dropped (matches the editor's non-blank rule).
        val summary = props.first("SUMMARY")?.value?.let(::unescapeText)?.let(::sanitizeText)
            ?.takeIf { it.isNotBlank() } ?: return null
        val dtStart = props.first("DTSTART") ?: return null
        val start = parseDateTime(dtStart, defaultZone) ?: return null
        val dtEnd = props.first("DTEND")
        val end = dtEnd?.let { parseDateTime(it, defaultZone) } ?: start
        val allDay = dtStart.params["VALUE"] == "DATE"
        // Audit F3a/F3b — store the zone the instant was actually computed in, resolved once here and
        // by the same resolver parseDateTime used. Storing the file's raw spelling instead meant an
        // unknown name (every Outlook export names zones the Windows way) was read as the device zone
        // to build the instant, then written to the row as a string nothing downstream could resolve:
        // the expander and the exporter both fell back to UTC, so each export/import round trip moved
        // the event by a whole offset and every recurring occurrence drifted at the DST boundary.
        val zoneId = when {
            allDay -> defaultZone.id
            dtStart.value.trim().endsWith("Z") -> "UTC"
            else -> TimeZones.normalize(dtStart.params["TZID"], defaultZone)
        }
        val recurrence = props.first("RRULE")
            ?.let { parseRRule(it.value, props["EXDATE"].orEmpty(), defaultZone) }
        return IcsEvent(
            title = summary,
            description = props.first("DESCRIPTION")?.value?.let(::unescapeText)?.let(::sanitizeText),
            location = props.first("LOCATION")?.value?.let(::unescapeText)?.let(::sanitizeText),
            startUtcMillis = start,
            endUtcMillis = maxOf(end, start),
            timeZoneId = zoneId,
            allDay = allDay,
            recurrence = recurrence,
            // Audit F2/F4 - UID bypassed the sanitiser every other imported string goes through.
            // Line breaks and control characters are dropped outright: never legitimate in a UID,
            // and they are the injection primitive.
            uid = props.first("UID")?.value?.let(::unescapeText)
                ?.filterNot { it == '\n' || it == '\r' || it.isISOControl() }
                ?.let(::sanitizeText)
                ?.takeIf { it.isNotBlank() },
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
                val zone = TimeZones.resolve(property.params["TZID"], defaultZone)
                LocalDateTime.parse(raw, LOCAL_STAMP).atZone(zone).toInstant().toEpochMilli()
            }
        }
    }.getOrNull()

    private fun parseRRule(
        value: String,
        exDateLines: List<IcsProperty>,
        defaultZone: ZoneId,
    ): RecurrenceRule? = runCatching {
        val parts = value.split(";").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0].uppercase() to kv[1] else null
        }.toMap()
        val freq = RecurrenceFreq.entries.firstOrNull { it.name == parts["FREQ"]?.uppercase() } ?: return null
        // Each EXDATE line carries its own parameters, so the lines are parsed separately rather than
        // concatenated: two lines may legitimately name different TZIDs, and a single merged parameter
        // set would read one of them in the other's zone. Duplicates are dropped — the same instant
        // excluded twice is the same exclusion.
        val exDates = exDateLines.flatMap { line ->
            line.value.split(",").mapNotNull { token ->
                parseDateTime(IcsProperty("EXDATE", line.params, token.trim()), defaultZone)
            }
        }.distinct()
        // COUNT below 1 is not a bound, and COUNT together with UNTIL is not a valid RRULE — both
        // violate an invariant of RecurrenceRule, and since this whole function is wrapped in
        // runCatching, throwing would silently strip the recurrence from an otherwise fine event.
        // Drop the bad bound instead, and let COUNT win over UNTIL exactly as the device importer
        // does (DeviceEventMapper), so the same file reads the same way through either path.
        val count = parts["COUNT"]?.toIntOrNull()?.takeIf { it >= 1 }
        val until = if (count == null) {
            parts["UNTIL"]?.let { parseDateTime(IcsProperty("UNTIL", emptyMap(), it), defaultZone) }
        } else {
            null
        }
        RecurrenceRule(
            freq = freq,
            interval = parts["INTERVAL"]?.toIntOrNull()
                ?.coerceIn(1, RecurrenceRule.MAX_INTERVAL) ?: 1,
            byWeekdays = parts["BYDAY"]?.split(",")?.mapNotNull { BYDAY_TO_ISO[it.trim().uppercase()] }?.toSet().orEmpty(),
            count = count,
            untilUtcMillis = until,
            // The EXDATEs were parsed just above and then dropped on the floor: every cancelled
            // occurrence came back to life on import, including on a round-trip through our own
            // export, which does write them. detekt had been reporting the symptom as an unused
            // `exDates` property.
            exDatesUtcMillis = exDates,
        )
    }.getOrNull()

    private fun parsePropertyLine(line: String): IcsProperty? {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val headParts = head.split(";")
        val name = headParts.first().uppercase()
        // RFC 5545 §3.2 lets a parameter value be quoted, and a producer may quote it even when it
        // need not. Unquoting here rather than at each reader keeps `TZID="Europe/Paris"` and
        // `TZID=Europe/Paris` the same parameter, which is what every reader below assumes.
        val params = headParts.drop(1).mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0].uppercase() to kv[1].removeSurrounding("\"") else null
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

    /**
     * The zone an event is exported in. The fallback is `ZoneId.of("UTC")` and not [ZoneOffset.UTC]
     * even though they denote the same instant: their ids differ (`UTC` vs `Z`), and [dateProperty]
     * branches on that id to emit the plain `…Z` form. With the offset's `Z` id the branch missed and
     * an unresolvable zone was exported as `TZID=Z`, which no reader understands.
     */
    private fun zoneOf(event: IcsEvent): ZoneId = TimeZones.resolve(event.timeZoneId, ZoneId.of("UTC"))

    private data class IcsProperty(val name: String, val params: Map<String, String>, val value: String)
}
