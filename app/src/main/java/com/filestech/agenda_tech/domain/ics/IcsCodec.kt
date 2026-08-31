package com.filestech.agenda_tech.domain.ics

import com.filestech.agenda_tech.domain.model.EventKind
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
 *
 * The **line syntax** it is written in — folding, unfolding, TEXT escaping, parameter quoting,
 * splitting a content line — lives in [IcsLines]. This object owns only what a calendar means.
 */
object IcsCodec {

    private const val PRODID = "-//Files Tech//Agenda Tech//EN"

    /**
     * Non-standard property carrying [EventKind]. RFC 5545 §3.8.8.2 reserves the `X-` space for
     * exactly this; a reader that does not know it ignores the line, so a birthday exported to
     * Google or Thunderbird simply arrives as the yearly all-day event it already is.
     */
    private const val PROP_KIND = "X-AGENDA-TECH-KIND"

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
        out.append("END:VCALENDAR").append(IcsLines.CRLF)
        return out.toString()
    }

    private fun StringBuilder.appendContentLine(line: String) {
        append(IcsLines.fold(line)).append(IcsLines.CRLF)
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
        out.appendContentLine("UID:${IcsLines.escapeText(uid)}")
        out.appendContentLine("DTSTAMP:${utcStamp(nowUtcMillis)}")
        out.appendContentLine(dateProperty("DTSTART", event.startUtcMillis, event))
        out.appendContentLine(dateProperty("DTEND", event.endUtcMillis, event))
        out.appendContentLine("SUMMARY:${IcsLines.escapeText(event.title)}")
        event.description?.takeIf { it.isNotBlank() }?.let { out.appendContentLine("DESCRIPTION:${IcsLines.escapeText(it)}") }
        event.location?.takeIf { it.isNotBlank() }?.let { out.appendContentLine("LOCATION:${IcsLines.escapeText(it)}") }
        event.recurrence?.let { rule ->
            out.appendContentLine("RRULE:${encodeRRule(rule)}")
            if (rule.exDatesUtcMillis.isNotEmpty()) {
                out.appendContentLine("EXDATE:${rule.exDatesUtcMillis.joinToString(",") { utcStamp(it) }}")
            }
        }
        // Written from the enum's own name, never from stored text, so nothing attacker-controlled
        // reaches the line (the concern audit F2/F4 raised about UID).
        if (event.kind != EventKind.NORMAL) {
            out.appendContentLine("$PROP_KIND:${event.kind.name}")
        }
        out.appendContentLine("END:VEVENT")
    }

    /**
     * Audit F3c — the `TZID` parameter is written from the **resolved** zone's id, never from the
     * stored string. The stored string is attacker-reachable (a hand-made `.atbak` carries the field
     * verbatim) and was the one place a value went into the output without passing through either
     * [IcsLines.escapeText] or a validator: a zone id holding a CRLF ended the content line and turned the rest
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
            // Compared on the RULES, not on the id string. An external reviewer pointed out that
            // `zone.id == "UTC"` misses an event stored as `"Z"` — which `ZoneId.of` accepts, which
            // `isCanonical` therefore approves, and which a `.ics` carrying `TZID=Z` produces. Such an
            // event exported as `TZID=Z`: the very unreadable id the fallback below was chosen to
            // avoid, reached from the other direction. `normalized()` folds every fixed-zero-offset
            // spelling — `UTC`, `Z`, `Etc/UTC`, `GMT` — onto the plain `…Z` form every reader knows.
            zone.normalized() == ZoneOffset.UTC -> "$name:${utcStamp(utcMillis)}"
            else -> {
                val local = Instant.ofEpochMilli(utcMillis).atZone(zone).toLocalDateTime()
                "$name;TZID=${IcsLines.quoteParam(zone.id)}:${local.format(LOCAL_STAMP)}"
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
        val lines = IcsLines.unfold(text)
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
                    val property = IcsLines.parse(line) ?: continue
                    current.getOrPut(property.name) { ArrayList() } += property
                }
            }
        }
        return events
    }

    private fun Map<String, List<IcsProperty>>.first(name: String): IcsProperty? = this[name]?.firstOrNull()

    private fun parseVEvent(props: Map<String, List<IcsProperty>>, defaultZone: ZoneId): IcsEvent? {
        // SEC-ICS3 — an event with no usable title is dropped (matches the editor's non-blank rule).
        //
        // The FIRST USABLE line, not simply the first: external review pointed out that plain
        // first-wins turns a file whose first SUMMARY is an empty placeholder — concatenated or
        // machine-merged exports do produce those — into a dropped event, where the previous
        // last-wins rule would have imported it. Skipping blanks keeps the tolerance without giving
        // an appended line the power to override a title a reader would already have shown.
        val summary = props["SUMMARY"].orEmpty()
            .firstNotNullOfOrNull { property ->
                sanitizeText(IcsLines.unescapeText(property.value)).takeIf { it.isNotBlank() }
            } ?: return null
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
            description = props.first("DESCRIPTION")?.value?.let(IcsLines::unescapeText)?.let(::sanitizeText),
            location = props.first("LOCATION")?.value?.let(IcsLines::unescapeText)?.let(::sanitizeText),
            startUtcMillis = start,
            endUtcMillis = maxOf(end, start),
            timeZoneId = zoneId,
            allDay = allDay,
            recurrence = recurrence,
            // Audit F2/F4 - UID bypassed the sanitiser every other imported string goes through.
            // Line breaks and control characters are dropped outright: never legitimate in a UID,
            // and they are the injection primitive.
            kind = props.first(PROP_KIND)?.value?.trim()?.uppercase()
                ?.let { name -> EventKind.entries.firstOrNull { it.name == name } }
                ?: EventKind.NORMAL,
            uid = props.first("UID")?.value?.let(IcsLines::unescapeText)
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

    /**
     * SEC-ICS2 — strip Unicode bidirectional-control characters from imported free text and cap its
     * length. An imported `.ics` is untrusted; without the strip an RLO/LRO override could spoof how
     * a title reads on screen/in the widget, and without the cap a single multi-MB folded field
     * could bloat the DB (same guard as the device-calendar import).
     */
    private fun sanitizeText(text: String): String = BidiSanitizer.stripAndCap(text)

    /**
     * The zone an event is exported in. The fallback is `ZoneId.of("UTC")` and not [ZoneOffset.UTC]
     * even though they denote the same instant: their ids differ (`UTC` vs `Z`), and [dateProperty]
     * branches on that id to emit the plain `…Z` form. With the offset's `Z` id the branch missed and
     * an unresolvable zone was exported as `TZID=Z`, which no reader understands.
     */
    private fun zoneOf(event: IcsEvent): ZoneId = TimeZones.resolve(event.timeZoneId, ZoneId.of("UTC"))
}
