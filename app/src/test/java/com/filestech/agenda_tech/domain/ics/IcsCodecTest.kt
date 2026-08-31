package com.filestech.agenda_tech.domain.ics

import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.EventKind
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.model.Weekday
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class IcsCodecTest {

    private val paris = ZoneId.of("Europe/Paris")
    private val now = 1_700_000_000_000L

    private fun parisMillis(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(paris).toInstant().toEpochMilli()

    private fun roundTrip(event: IcsEvent): IcsEvent {
        val text = IcsCodec.encode(listOf(event), now)
        return IcsCodec.decode(text, paris).single()
    }

    @Test
    fun `export UID is derived from the event identity, not its list position`() {
        val base = Event(
            id = 5, calendarId = 1, title = "A",
            startUtcMillis = parisMillis(2025, 6, 1, 9, 0),
            endUtcMillis = parisMillis(2025, 6, 1, 10, 0),
            timeZoneId = "Europe/Paris",
        )
        // No source uid → stable row-based uid.
        assertThat(base.toIcsEvent().uid).isEqualTo("row-5")
        // Previously imported (.ics) event → keeps the original uid so re-export/re-import dedups.
        assertThat(base.copy(sourceUid = "ics:external@example.com").toIcsEvent().uid)
            .isEqualTo("external@example.com")
    }

    @Test
    fun `timed event round-trips with its time zone preserved`() {
        val event = IcsEvent(
            title = "Réunion",
            description = "Ordre du jour",
            location = "Bureau",
            startUtcMillis = parisMillis(2025, 6, 1, 9, 0),
            endUtcMillis = parisMillis(2025, 6, 1, 10, 0),
            timeZoneId = "Europe/Paris",
            allDay = false,
            recurrence = null,
        )
        // decode() captures the synthetic UID that encode() emits (used for idempotent re-import);
        // every other field must round-trip identically.
        assertThat(roundTrip(event).copy(uid = null)).isEqualTo(event)
    }

    @Test
    fun `all-day event round-trips`() {
        val start = LocalDate.of(2025, 6, 1).atStartOfDay(paris).toInstant().toEpochMilli()
        val end = LocalDate.of(2025, 6, 2).atStartOfDay(paris).toInstant().toEpochMilli()
        val event = IcsEvent(
            title = "Congé",
            description = null,
            location = null,
            startUtcMillis = start,
            endUtcMillis = end,
            timeZoneId = "Europe/Paris",
            allDay = true,
            recurrence = null,
        )
        val decoded = roundTrip(event)
        assertThat(decoded.allDay).isTrue()
        assertThat(decoded.startUtcMillis).isEqualTo(start)
        assertThat(decoded.endUtcMillis).isEqualTo(end)
        assertThat(decoded.title).isEqualTo("Congé")
    }

    @Test
    fun `recurring event round-trips FREQ INTERVAL BYDAY and COUNT`() {
        val event = IcsEvent(
            title = "Standup",
            description = null,
            location = null,
            startUtcMillis = parisMillis(2025, 6, 2, 9, 0),
            endUtcMillis = parisMillis(2025, 6, 2, 9, 15),
            timeZoneId = "Europe/Paris",
            allDay = false,
            recurrence = RecurrenceRule(
                freq = RecurrenceFreq.WEEKLY,
                interval = 2,
                byWeekdays = setOf(Weekday.MONDAY, Weekday.WEDNESDAY),
                count = 10,
            ),
        )
        val decoded = roundTrip(event)
        assertThat(decoded.recurrence).isEqualTo(event.recurrence)
    }

    @Test
    fun `cancelled occurrences survive the round-trip instead of coming back to life`() {
        // parseRRule parsed the EXDATEs and then never passed them to the rule, so an occurrence the
        // user had deleted reappeared as soon as the file was imported — including a re-import of our
        // own export, which does write EXDATE.
        val excluded = parisMillis(2025, 6, 9, 18, 0)
        val event = IcsEvent(
            title = "Cours",
            description = null,
            location = null,
            startUtcMillis = parisMillis(2025, 6, 2, 18, 0),
            endUtcMillis = parisMillis(2025, 6, 2, 19, 0),
            timeZoneId = "Europe/Paris",
            allDay = false,
            recurrence = RecurrenceRule(
                freq = RecurrenceFreq.WEEKLY,
                count = 4,
                exDatesUtcMillis = listOf(excluded),
            ),
        )

        assertThat(roundTrip(event).recurrence?.exDatesUtcMillis).containsExactly(excluded)
    }

    @Test
    fun `recurrence UNTIL round-trips`() {
        val until = parisMillis(2025, 12, 31, 22, 59)
        val event = IcsEvent(
            title = "Cours",
            description = null,
            location = null,
            startUtcMillis = parisMillis(2025, 6, 2, 18, 0),
            endUtcMillis = parisMillis(2025, 6, 2, 19, 0),
            timeZoneId = "Europe/Paris",
            allDay = false,
            recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY, untilUtcMillis = until),
        )
        assertThat(roundTrip(event).recurrence?.untilUtcMillis).isEqualTo(until)
    }

    @Test
    fun `text with commas semicolons and newlines is escaped and restored`() {
        val event = IcsEvent(
            title = "A, B; C",
            description = "line1\nline2",
            location = null,
            startUtcMillis = parisMillis(2025, 6, 1, 9, 0),
            endUtcMillis = parisMillis(2025, 6, 1, 10, 0),
            timeZoneId = "Europe/Paris",
            allDay = false,
            recurrence = null,
        )
        val decoded = roundTrip(event)
        assertThat(decoded.title).isEqualTo("A, B; C")
        assertThat(decoded.description).isEqualTo("line1\nline2")
    }

    @Test
    fun `decodes a UTC event from an external file and ignores unknown properties`() {
        val text = buildString {
            append("BEGIN:VCALENDAR\r\n")
            append("VERSION:2.0\r\n")
            append("BEGIN:VEVENT\r\n")
            append("UID:external@example.com\r\n")
            append("X-CUSTOM-PROP:whatever\r\n")
            append("DTSTART:20250601T080000Z\r\n")
            append("DTEND:20250601T090000Z\r\n")
            append("SUMMARY:External\r\n")
            append("END:VEVENT\r\n")
            append("END:VCALENDAR\r\n")
        }
        val decoded = IcsCodec.decode(text, paris).single()
        assertThat(decoded.title).isEqualTo("External")
        assertThat(decoded.timeZoneId).isEqualTo("UTC")
        assertThat(decoded.startUtcMillis).isEqualTo(
            LocalDateTime.of(2025, 6, 1, 8, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli(),
        )
        // FIAB-1 — the VEVENT UID is captured so a re-import can dedup instead of duplicating.
        assertThat(decoded.uid).isEqualTo("external@example.com")
    }

    @Test
    fun `bidi control characters are stripped from imported text`() {
        val rlo = "‮"
        val text = buildString {
            append("BEGIN:VCALENDAR\r\n")
            append("BEGIN:VEVENT\r\n")
            append("DTSTART:20250601T080000Z\r\n")
            append("SUMMARY:Safe${rlo}spoof\r\n")
            append("END:VEVENT\r\n")
            append("END:VCALENDAR\r\n")
        }
        val decoded = IcsCodec.decode(text, paris).single()
        assertThat(decoded.title).isEqualTo("Safespoof")
        assertThat(decoded.title.contains(rlo)).isFalse()
    }

    @Test
    fun `an event with a blank summary is dropped on import`() {
        val text = buildString {
            append("BEGIN:VCALENDAR\r\n")
            append("BEGIN:VEVENT\r\n")
            append("DTSTART:20250601T080000Z\r\n")
            append("SUMMARY: \r\n")
            append("END:VEVENT\r\n")
            append("END:VCALENDAR\r\n")
        }
        assertThat(IcsCodec.decode(text, paris)).isEmpty()
    }

    @Test
    fun `encode wraps events in a VCALENDAR envelope`() {
        val text = IcsCodec.encode(emptyList(), now)
        assertThat(text).contains("BEGIN:VCALENDAR")
        assertThat(text).contains("END:VCALENDAR")
    }

    // --- Audit F1/F5/F7 — the import paths must bound INTERVAL --------------

    @Test
    fun `an absurd RRULE INTERVAL is clamped on import and survives expansion`() {
        // Audit F1/F5/F7. Unclamped, this interval made the YEARLY branch of the expander compute a
        // year past YearMonth's +/-999,999,999 limit and throw a DateTimeException on the render path
        // of every view and of the widget — one imported event was enough to crash the app on every
        // launch, with no in-app way to reach and delete it.
        val text = buildString {
            append("BEGIN:VCALENDAR\r\n")
            append("BEGIN:VEVENT\r\n")
            append("DTSTART:20250601T080000Z\r\n")
            append("DTEND:20250601T090000Z\r\n")
            append("SUMMARY:Piégé\r\n")
            append("RRULE:FREQ=YEARLY;INTERVAL=1000000000\r\n")
            append("END:VEVENT\r\n")
            append("END:VCALENDAR\r\n")
        }

        val decoded = IcsCodec.decode(text, paris).single()
        assertThat(decoded.recurrence?.interval).isEqualTo(RecurrenceRule.MAX_INTERVAL)

        // The point of the bound: expanding the imported event no longer throws.
        val event = decoded.toEvent(calendarId = 1)
        val occurrences = RecurrenceExpander().expand(
            event,
            windowStartUtcMillis = event.startUtcMillis,
            windowEndUtcMillis = event.startUtcMillis + 2 * 24 * 60 * 60 * 1000L,
        )
        assertThat(occurrences.map { it.startUtcMillis }).containsExactly(event.startUtcMillis)
    }

    @Test
    fun `a COUNT below 1 drops the bound instead of the whole recurrence`() {
        // RecurrenceRule requires COUNT >= 1, and parseRRule is wrapped in runCatching: throwing here
        // stripped the recurrence from an otherwise perfectly good event, silently turning a repeating
        // appointment into a one-off.
        val decoded = IcsCodec.decode(icsWithRRule("FREQ=WEEKLY;COUNT=0"), paris).single()

        assertThat(decoded.recurrence).isNotNull()
        assertThat(decoded.recurrence?.freq).isEqualTo(RecurrenceFreq.WEEKLY)
        assertThat(decoded.recurrence?.count).isNull()
    }

    @Test
    fun `COUNT and UNTIL together resolve to COUNT rather than losing the recurrence`() {
        // An RRULE may carry at most one bound; a file with both violated the domain invariant and
        // cost the event its whole recurrence. COUNT wins, matching the device importer.
        val decoded = IcsCodec.decode(
            icsWithRRule("FREQ=WEEKLY;COUNT=5;UNTIL=20251231T225900Z"),
            paris,
        ).single()

        assertThat(decoded.recurrence?.count).isEqualTo(5)
        assertThat(decoded.recurrence?.untilUtcMillis).isNull()
    }

    private fun icsWithRRule(rrule: String): String = buildString {
        append("BEGIN:VCALENDAR\r\n")
        append("BEGIN:VEVENT\r\n")
        append("DTSTART:20250601T080000Z\r\n")
        append("DTEND:20250601T090000Z\r\n")
        append("SUMMARY:Récurrent\r\n")
        append("RRULE:$rrule\r\n")
        append("END:VEVENT\r\n")
        append("END:VCALENDAR\r\n")
    }

    // --- Audit F2/F4/F9/F10/F11 — no value may forge a content line ---------

    @Test
    fun `a UID carrying a line break produces no extra content line on export`() {
        // Audit F2/F4. The UID was the one TEXT value written raw, and it is attacker-controlled on
        // any imported event: a real newline in it became a content line, so the .ics the user then
        // shares could carry whole VEVENTs somebody else wrote, under their name.
        // A UID whose escaped breaks, once unescaped, spell out a second complete VEVENT.
        val payload = listOf(
            "victim@example.com",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "SUMMARY:Forgé",
            "DTSTART:20250601T080000Z",
            "UID:forged@example.com",
        ).joinToString("\\n")
        val hostile = buildString {
            append("BEGIN:VCALENDAR\r\n")
            append("BEGIN:VEVENT\r\n")
            append("DTSTART:20250601T080000Z\r\n")
            append("SUMMARY:Innocent\r\n")
            append("UID:$payload\r\n")
            append("END:VEVENT\r\n")
            append("END:VCALENDAR\r\n")
        }

        // Import side: the escaped break is unescaped, then dropped before it can be stored.
        val imported = IcsCodec.decode(hostile, paris).single()
        assertThat(imported.uid).doesNotContain("\n")
        assertThat(imported.uid).doesNotContain("\r")

        // Export side, independently: even a UID that reached us with a raw break stays on one line.
        val exported = IcsCodec.encode(
            listOf(imported.copy(uid = "victim@example.com\r\nEND:VEVENT\r\nBEGIN:VEVENT")),
            now,
        )
        assertThat(contentLines(exported).count { it == "BEGIN:VEVENT" }).isEqualTo(1)
        assertThat(contentLines(exported).count { it.startsWith("UID:") }).isEqualTo(1)
        assertThat(IcsCodec.decode(exported, paris)).hasSize(1)
    }

    @Test
    fun `a carriage return in a title does not break the content line`() {
        // Audit F9/F10/F11. escapeText neutralised LF but not CR, and every unfolder treats a bare CR
        // as a line break — so a CR pasted into a title split SUMMARY and turned the remainder into a
        // forged property.
        val event = IcsEvent(
            title = "Rendez-vous\rDESCRIPTION:injecté",
            description = null,
            location = null,
            startUtcMillis = parisMillis(2025, 6, 1, 9, 0),
            endUtcMillis = parisMillis(2025, 6, 1, 10, 0),
            timeZoneId = "Europe/Paris",
            allDay = false,
            recurrence = null,
        )

        val exported = IcsCodec.encode(listOf(event), now)
        val lines = contentLines(exported)
        assertThat(lines.count { it.startsWith("SUMMARY:") }).isEqualTo(1)
        assertThat(lines.none { it.startsWith("DESCRIPTION:") }).isTrue()
        assertThat(lines.single { it.startsWith("SUMMARY:") })
            .isEqualTo("SUMMARY:Rendez-vous\\nDESCRIPTION:injecté")
    }

    // --- Files this codec did not write -------------------------------------
    //
    // Everything above round-trips our own output, which is exactly the shape of test that cannot see
    // audit F3, F8 or F14: they are all defects in reading, or re-reading, what somebody else wrote.
    // What follows is built from the forms Outlook, Exchange and Google Calendar actually emit.

    @Test
    fun `an Outlook file naming its zone the Windows way is read in that zone`() {
        // Verbatim shape of an Outlook export: a VTIMEZONE block we do not parse, and a DTSTART whose
        // TZID is a Windows zone name. ZoneId.of() rejects that name, so the importer fell back to the
        // device zone for the instant and stored the raw string as the event's zone.
        val decoded = IcsCodec.decode(OUTLOOK_FILE, paris).single()

        assertThat(decoded.title).isEqualTo("Réunion projet")
        assertThat(decoded.startUtcMillis).isEqualTo(parisMillis(2025, 6, 2, 14, 0))
        // The stored zone is the zone the instant was computed in, and it is one every later reader
        // resolves — the expander and the exporter both fell back to UTC on the raw Windows name.
        assertThat(decoded.timeZoneId).isEqualTo("Europe/Paris")
    }

    @Test
    fun `a Windows-named zone no longer moves the event on each export and re-import`() {
        // Audit F3a. Export resolved the stored zone to UTC and wrote the wall-clock time in UTC under
        // the original TZID; the re-import then read that time in the *device* zone. Every round trip
        // therefore shifted the event by one whole offset — twice round, two hours in June.
        val first = IcsCodec.decode(OUTLOOK_FILE, paris).single()
        val second = IcsCodec.decode(IcsCodec.encode(listOf(first), now), paris).single()
        val third = IcsCodec.decode(IcsCodec.encode(listOf(second), now), paris).single()

        assertThat(second.startUtcMillis).isEqualTo(first.startUtcMillis)
        assertThat(third.startUtcMillis).isEqualTo(first.startUtcMillis)
        assertThat(third.timeZoneId).isEqualTo("Europe/Paris")
    }

    @Test
    fun `a quoted TZID parameter names the same zone as an unquoted one`() {
        val quoted = EXTERNAL_HEADER +
            "BEGIN:VEVENT\r\n" +
            "UID:quoted@example.com\r\n" +
            "DTSTART;TZID=\"Europe/Paris\":20250602T140000\r\n" +
            "DTEND;TZID=\"Europe/Paris\":20250602T150000\r\n" +
            "SUMMARY:Quoted\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"

        val decoded = IcsCodec.decode(quoted, ZoneId.of("UTC")).single()
        assertThat(decoded.timeZoneId).isEqualTo("Europe/Paris")
        assertThat(decoded.startUtcMillis).isEqualTo(parisMillis(2025, 6, 2, 14, 0))
    }

    @Test
    fun `every EXDATE line is honoured, not just the last one`() {
        // Audit F8, found by both external reviewers. Properties were stored one per name, so of the
        // three EXDATE lines Google Calendar writes for three cancelled occurrences, two were dropped
        // and those occurrences came back to life on import.
        val file = EXTERNAL_HEADER +
            "BEGIN:VEVENT\r\n" +
            "UID:weekly@example.com\r\n" +
            "DTSTART;TZID=Europe/Paris:20250602T180000\r\n" +
            "DTEND;TZID=Europe/Paris:20250602T190000\r\n" +
            "RRULE:FREQ=WEEKLY;COUNT=6\r\n" +
            "SUMMARY:Cours\r\n" +
            "EXDATE;TZID=Europe/Paris:20250609T180000\r\n" +
            "EXDATE;TZID=Europe/Paris:20250616T180000\r\n" +
            "EXDATE;TZID=Europe/Paris:20250623T180000\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"

        val decoded = IcsCodec.decode(file, paris).single()

        assertThat(decoded.recurrence?.exDatesUtcMillis).containsExactly(
            parisMillis(2025, 6, 9, 18, 0),
            parisMillis(2025, 6, 16, 18, 0),
            parisMillis(2025, 6, 23, 18, 0),
        )
    }

    @Test
    fun `EXDATE lines are read each in its own zone`() {
        // Why the lines are parsed separately rather than concatenated: each carries its own
        // parameters, and merging them would read one line's instants in the other line's zone.
        val file = EXTERNAL_HEADER +
            "BEGIN:VEVENT\r\n" +
            "UID:mixed@example.com\r\n" +
            "DTSTART;TZID=Europe/Paris:20250602T180000\r\n" +
            "DTEND;TZID=Europe/Paris:20250602T190000\r\n" +
            "RRULE:FREQ=WEEKLY;COUNT=6\r\n" +
            "SUMMARY:Cours\r\n" +
            "EXDATE;TZID=Europe/Paris:20250609T180000\r\n" +
            "EXDATE:20250616T160000Z\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"

        // 16:00 UTC is 18:00 in Paris in June — the same occurrence, named the other way.
        assertThat(IcsCodec.decode(file, paris).single().recurrence?.exDatesUtcMillis).containsExactly(
            parisMillis(2025, 6, 9, 18, 0),
            parisMillis(2025, 6, 16, 18, 0),
        )
    }

    @Test
    fun `a repeated SUMMARY cannot override the one a reader would show`() {
        val file = EXTERNAL_HEADER +
            "BEGIN:VEVENT\r\n" +
            "UID:dup@example.com\r\n" +
            "DTSTART:20250601T080000Z\r\n" +
            "SUMMARY:Dentiste\r\n" +
            "SUMMARY:Autre chose\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"

        assertThat(IcsCodec.decode(file, paris).single().title).isEqualTo("Dentiste")
    }

    @Test
    fun `an empty first SUMMARY does not throw the event away`() {
        // External review of this lot: plain first-wins turns a concatenated or machine-merged export
        // whose first SUMMARY is an empty placeholder into a dropped event — where the last-wins rule
        // it replaced would have imported it. First USABLE line, not first line.
        val file = EXTERNAL_HEADER +
            "BEGIN:VEVENT\r\n" +
            "UID:blankfirst@example.com\r\n" +
            "DTSTART:20250601T080000Z\r\n" +
            "SUMMARY:\r\n" +
            "SUMMARY:Dentiste\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"

        assertThat(IcsCodec.decode(file, paris).single().title).isEqualTo("Dentiste")
    }

    @Test
    fun `a fixed-offset zone survives the round-trip instead of dropping the event`() {
        // External review of this lot, CONFIRMÉ. TimeZones accepts a fixed offset on purpose, so
        // `+02:00` is a value this codec can be asked to export. Written unquoted it produced
        // `DTSTART;TZID=+02:00:20250601T100000`, and parsePropertyLine split on the FIRST colon: the
        // parameter became `TZID=+02` and the value `00:20250601T100000`, which parses as nothing.
        // Our own reader dropped the event, from our own export.
        val event = IcsEvent(
            title = "Décalé",
            description = null,
            location = null,
            startUtcMillis = parisMillis(2025, 6, 1, 10, 0),
            endUtcMillis = parisMillis(2025, 6, 1, 11, 0),
            timeZoneId = "+02:00",
            allDay = false,
            recurrence = null,
        )

        val exported = IcsCodec.encode(listOf(event), now)
        // RFC 5545 §3.2 requires the quotes as soon as the value holds a colon.
        assertThat(contentLines(exported).single { it.startsWith("DTSTART") })
            .isEqualTo("DTSTART;TZID=\"+02:00\":20250601T100000")

        val decoded = IcsCodec.decode(exported, paris).single()
        assertThat(decoded.startUtcMillis).isEqualTo(event.startUtcMillis)
        assertThat(decoded.timeZoneId).isEqualTo("+02:00")
    }

    @Test
    fun `a quoted parameter value cannot smuggle a parameter of its own`() {
        // The parameter split has to honour quotes for the same reason the value split does — being
        // quote-aware for one delimiter and blind for the other is half a reader.
        //
        // This is not tidiness. Splitting on every `;` lets a value that is quoted *precisely so that
        // it may contain one* be cut into extra parameters, and the reader then obeys them. Here a
        // harmless X- parameter smuggles `VALUE=DATE`, which makes the event all-day AND makes the
        // date-time parse fail — so the event is dropped outright.
        //
        // The first version of this test used `X-ODD="a;b"` and stayed green with the fix removed:
        // the split produced garbage parameters that happened to affect nothing. Measured, then made
        // discriminating.
        val file = EXTERNAL_HEADER +
            "BEGIN:VEVENT\r\n" +
            "UID:semi@example.com\r\n" +
            "DTSTART;X-ODD=\"junk;VALUE=DATE;more\";TZID=Europe/Paris:20250602T140000\r\n" +
            "SUMMARY:Point-virgule\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"

        val decoded = IcsCodec.decode(file, ZoneId.of("UTC")).single()
        assertThat(decoded.allDay).isFalse()
        assertThat(decoded.timeZoneId).isEqualTo("Europe/Paris")
        assertThat(decoded.startUtcMillis).isEqualTo(parisMillis(2025, 6, 2, 14, 0))
    }

    @Test
    fun `a stored zone nothing can resolve cannot inject a property into the export`() {
        // Audit F3c. TZID was written from the stored string, the one value that reached the output
        // through neither escapeText nor a validator — and a hand-made .atbak carries that field
        // verbatim into the database. The export is now written from the resolved ZoneId's id, which
        // cannot hold a line break.
        val event = IcsEvent(
            title = "Rendez-vous",
            description = null,
            location = null,
            startUtcMillis = parisMillis(2025, 6, 1, 9, 0),
            endUtcMillis = parisMillis(2025, 6, 1, 10, 0),
            timeZoneId = "Europe/Paris\r\nDESCRIPTION:injecté\r\nX-EVIL:1",
            allDay = false,
            recurrence = null,
        )

        val lines = contentLines(IcsCodec.encode(listOf(event), now))
        assertThat(lines.none { it.startsWith("DESCRIPTION:") }).isTrue()
        assertThat(lines.none { it.startsWith("X-EVIL") }).isTrue()
        assertThat(lines.none { it.contains("injecté") }).isTrue()
        // An unresolvable zone falls back to UTC, which is emitted as the plain "…Z" form rather than
        // as TZID=Z — an id no reader understands.
        assertThat(lines.single { it.startsWith("DTSTART") }).isEqualTo("DTSTART:20250601T070000Z")
    }

    @Test
    fun `an emoji is never cut in half by the fold, at whatever offset it falls`() {
        // Audit F14. The fold counted Kotlin Chars, which are UTF-16 code units: an emoji is two of
        // them, and a fold landing between the two split the surrogate pair. toByteArray(UTF_8) maps
        // an unpaired surrogate to '?', so the emoji left the exporter as "??" — silently, and with no
        // way to recover it from the file.
        //
        // Two things this test had to be built around, both found by re-introducing the defect and
        // watching an earlier version of it stay green:
        //
        //  - the assertion has to cross `toByteArray(UTF_8)`. The encoder's own String still holds the
        //    unpaired surrogate; nothing is wrong until the exporter writes bytes, which is where
        //    IcsViewModel.export does it.
        //  - one title tests one offset. The split needs the boundary to fall exactly between two
        //    units of one emoji, so the offset is swept instead of pinned — a single hand-picked title
        //    lands on the boundary or misses it by luck, and a test that passes by luck is worse than
        //    no test.
        for (pad in 0..8) {
            val title = "Anniversaire" + " ".repeat(pad) + "🎂".repeat(40)
            val event = IcsEvent(
                title = title,
                description = null,
                location = null,
                startUtcMillis = parisMillis(2025, 6, 1, 9, 0),
                endUtcMillis = parisMillis(2025, 6, 1, 10, 0),
                timeZoneId = "Europe/Paris",
                allDay = false,
                recurrence = null,
            )

            val written = IcsCodec.encode(listOf(event), now)
                .toByteArray(Charsets.UTF_8)
                .toString(Charsets.UTF_8)

            assertThat(written).doesNotContain("?")
            assertThat(IcsCodec.decode(written, paris).single().title).isEqualTo(title)
        }
    }

    @Test
    fun `no exported line exceeds the 75 octets RFC 5545 allows`() {
        // The bound RFC 5545 states is in octets, and it counts the space that opens a continuation
        // line. Folding at 73 *characters* let a line of accented text run well past it.
        val event = IcsEvent(
            title = "Rendez-vous chez le médecin généraliste ".repeat(6),
            description = "Ordre du jour très détaillé — ".repeat(10) + "🎂🎉🎁",
            location = "Cabinet médical, 12 rue de l'Église, Saint-Étienne-de-Tinée",
            startUtcMillis = parisMillis(2025, 6, 1, 9, 0),
            endUtcMillis = parisMillis(2025, 6, 1, 10, 0),
            timeZoneId = "Europe/Paris",
            allDay = false,
            recurrence = null,
        )

        val overLong = IcsCodec.encode(listOf(event), now)
            .split("\r\n")
            .filter { it.toByteArray(Charsets.UTF_8).size > 75 }
        assertThat(overLong).isEmpty()
        assertThat(roundTrip(event).title).isEqualTo(event.title)
        assertThat(roundTrip(event).description).isEqualTo(event.description)
    }

    @Test
    fun `a Unicode line separator in a title cannot forge a property in somebody else's reader`() {
        // Audit S8. U+2028/U+2029/U+0085 are inert for our own unfold — which is why every existing
        // round-trip test stayed green — but they terminate a line for java.util.Scanner, for
        // java.util.regex outside UNIX_LINES, and for several iCalendar readers. The exposure is
        // outgoing: a file the user exports and passes on.
        val event = IcsEvent(
            title = "Rendez-vous DESCRIPTION:injecté X-EVIL:1SUMMARY:usurpé",
            description = null,
            location = null,
            startUtcMillis = parisMillis(2025, 6, 1, 9, 0),
            endUtcMillis = parisMillis(2025, 6, 1, 10, 0),
            timeZoneId = "Europe/Paris",
            allDay = false,
            recurrence = null,
        )

        val exported = IcsCodec.encode(listOf(event), now)

        // Not one of the three survives into the file, so no reader — whatever it splits on — can see
        // a line break where we did not write one.
        assertThat(exported).doesNotContain(" ")
        assertThat(exported).doesNotContain(" ")
        assertThat(exported).doesNotContain("")
        assertThat(contentLines(exported).count { it.startsWith("SUMMARY:") }).isEqualTo(1)
        assertThat(contentLines(exported).none { it.startsWith("DESCRIPTION:") }).isTrue()
        assertThat(contentLines(exported).none { it.startsWith("X-EVIL") }).isTrue()
    }

    @Test
    fun `an ellipsis survives the export untouched`() {
        // Pins a refutation. An external reviewer read the invisible U+0085 in `escapeText` as U+2026
        // and reported that every "…" in a title is turned into an escaped line break. Measured false
        // — the bytes were always U+2028/U+2029/U+0085 — but the only durable answer to "is that
        // character the one you think it is" is a test, not a second reading of the same invisible
        // glyph. The source now spells them as `\u….` escapes for the same reason.
        val event = IcsEvent(
            title = "À suivre… et la suite",
            description = null,
            location = null,
            startUtcMillis = parisMillis(2025, 6, 1, 9, 0),
            endUtcMillis = parisMillis(2025, 6, 1, 10, 0),
            timeZoneId = "Europe/Paris",
            allDay = false,
            recurrence = null,
        )

        val exported = IcsCodec.encode(listOf(event), now)
        assertThat(contentLines(exported).single { it.startsWith("SUMMARY:") })
            .isEqualTo("SUMMARY:À suivre… et la suite")
        assertThat(roundTrip(event).title).isEqualTo("À suivre… et la suite")
    }

    @Test
    fun `an event stored as Z exports as the plain UTC form, not as TZID=Z`() {
        // Found by external review. `ZoneId.of("Z")` is valid, so `"Z"` passes `isCanonical` and can
        // legitimately reach the exporter — a `.ics` carrying `TZID=Z` produces exactly that. The
        // branch matched on the literal id `"UTC"`, so this event was written `TZID=Z`, which is the
        // unreadable id the UTC fallback exists to avoid, reached from the other side.
        val event = IcsEvent(
            title = "Zoulou",
            description = null,
            location = null,
            startUtcMillis = parisMillis(2025, 6, 1, 10, 0),
            endUtcMillis = parisMillis(2025, 6, 1, 11, 0),
            timeZoneId = "Z",
            allDay = false,
            recurrence = null,
        )

        val lines = contentLines(IcsCodec.encode(listOf(event), now))
        assertThat(lines.single { it.startsWith("DTSTART") }).isEqualTo("DTSTART:20250601T080000Z")
        assertThat(lines.none { it.contains("TZID=Z") }).isTrue()
        // And it still round-trips to a usable zone rather than being dropped.
        assertThat(roundTrip(event).startUtcMillis).isEqualTo(event.startUtcMillis)
    }

    @Test
    fun `an ics file reads the same under a locale with its own casing rules`() {
        // A security review claimed the nine `uppercase()`/`lowercase()` calls in this codec break
        // under tr-TR (dotless I), which would mean `uid`, `tzid` and `daily` stop matching and the
        // same file means two different things depending on the phone's language.
        //
        // REFUTED, and pinned: Kotlin's argument-less `uppercase()`/`lowercase()` are
        // locale-independent by design (`Locale.ROOT`) — that is the whole reason they replaced
        // `toUpperCase()`/`toLowerCase()`. The trap is real; it is already avoided. This test fails
        // loudly the day someone "fixes" one of them to `uppercase(Locale.getDefault())`.
        val lowerCased = EXTERNAL_HEADER +
            "BEGIN:VEVENT\r\n" +
            "uid:turkish@example.com\r\n" +
            "dtstart;tzid=Europe/Paris:20250602T140000\r\n" +
            "dtend;tzid=Europe/Paris:20250602T150000\r\n" +
            "summary:Réunion\r\n" +
            "rrule:freq=daily;count=3\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"

        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val decoded = IcsCodec.decode(lowerCased, ZoneId.of("UTC")).single()
            assertThat(decoded.title).isEqualTo("Réunion")
            assertThat(decoded.uid).isEqualTo("turkish@example.com")
            assertThat(decoded.timeZoneId).isEqualTo("Europe/Paris")
            assertThat(decoded.startUtcMillis).isEqualTo(parisMillis(2025, 6, 2, 14, 0))
            assertThat(decoded.recurrence?.freq).isEqualTo(RecurrenceFreq.DAILY)
            assertThat(decoded.recurrence?.count).isEqualTo(3)
        } finally {
            Locale.setDefault(previous)
        }
    }

    /** Unfold [text] the way any RFC 5545 reader does, to count the content lines it really carries. */
    private fun contentLines(text: String): List<String> {
        val lines = mutableListOf<String>()
        // Split on CR and LF alike: a reader that sees a bare CR breaks the line there too, which is
        // exactly the injection this asserts against.
        for (raw in text.split("\r\n", "\r", "\n")) {
            if (raw.isEmpty()) continue
            if (raw.startsWith(" ") && lines.isNotEmpty()) {
                lines[lines.lastIndex] = lines.last() + raw.substring(1)
            } else {
                lines += raw
            }
        }
        return lines
    }

    private companion object {
        const val EXTERNAL_HEADER =
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Somebody Else//EN\r\nCALSCALE:GREGORIAN\r\n"

        /**
         * The shape Outlook and Exchange actually export: a VTIMEZONE block (which this codec does not
         * parse, and does not need to) and a `TZID` naming the zone the Windows way.
         */
        const val OUTLOOK_FILE = EXTERNAL_HEADER +
            "BEGIN:VTIMEZONE\r\n" +
            "TZID:Romance Standard Time\r\n" +
            "BEGIN:STANDARD\r\n" +
            "DTSTART:16011028T030000\r\n" +
            "TZOFFSETFROM:+0200\r\n" +
            "TZOFFSETTO:+0100\r\n" +
            "RRULE:FREQ=YEARLY;BYDAY=-1SU;BYMONTH=10\r\n" +
            "END:STANDARD\r\n" +
            "BEGIN:DAYLIGHT\r\n" +
            "DTSTART:16010325T020000\r\n" +
            "TZOFFSETFROM:+0100\r\n" +
            "TZOFFSETTO:+0200\r\n" +
            "RRULE:FREQ=YEARLY;BYDAY=-1SU;BYMONTH=3\r\n" +
            "END:DAYLIGHT\r\n" +
            "END:VTIMEZONE\r\n" +
            "BEGIN:VEVENT\r\n" +
            "UID:040000008200E00074C5B7101A82E00800000000@outlook.com\r\n" +
            "DTSTAMP:20250520T101500Z\r\n" +
            "DTSTART;TZID=Romance Standard Time:20250602T140000\r\n" +
            "DTEND;TZID=Romance Standard Time:20250602T150000\r\n" +
            "SUMMARY:Réunion projet\r\n" +
            "END:VEVENT\r\n" +
            "END:VCALENDAR\r\n"
    }

    @Test
    fun `a birthday keeps its kind through an ics round trip`() {
        val birthday = IcsEvent(
            title = "Paul",
            description = null,
            location = null,
            startUtcMillis = parisMillis(1984, 3, 12, 0, 0),
            endUtcMillis = parisMillis(1984, 3, 13, 0, 0),
            timeZoneId = "Europe/Paris",
            allDay = true,
            recurrence = RecurrenceRule(freq = RecurrenceFreq.YEARLY),
            kind = EventKind.BIRTHDAY,
        )

        val text = IcsCodec.encode(listOf(birthday), now)
        assertThat(text).contains("X-AGENDA-TECH-KIND:BIRTHDAY")
        assertThat(IcsCodec.decode(text, paris).single().kind).isEqualTo(EventKind.BIRTHDAY)
    }

    @Test
    fun `an ordinary event writes no kind property at all`() {
        // The X- line is noise in every other calendar app, so it is only emitted when it says
        // something. A file full of `X-AGENDA-TECH-KIND:NORMAL` would also make diffs unreadable.
        val plain = IcsEvent(
            title = "Dentiste",
            description = null,
            location = null,
            startUtcMillis = parisMillis(2026, 6, 1, 9, 0),
            endUtcMillis = parisMillis(2026, 6, 1, 10, 0),
            timeZoneId = "Europe/Paris",
            allDay = false,
            recurrence = null,
        )
        assertThat(IcsCodec.encode(listOf(plain), now)).doesNotContain("X-AGENDA-TECH-KIND")
    }

    @Test
    fun `a file written by any other calendar reads back as an ordinary event`() {
        val foreign = buildString {
            append("BEGIN:VCALENDAR\r\n")
            append("VERSION:2.0\r\n")
            append("BEGIN:VEVENT\r\n")
            append("UID:x@example.com\r\n")
            append("DTSTART;VALUE=DATE:19840312\r\n")
            append("DTEND;VALUE=DATE:19840313\r\n")
            append("SUMMARY:Paul\r\n")
            append("RRULE:FREQ=YEARLY\r\n")
            append("END:VEVENT\r\n")
            append("END:VCALENDAR\r\n")
        }

        assertThat(IcsCodec.decode(foreign, paris).single().kind).isEqualTo(EventKind.NORMAL)
    }
}
