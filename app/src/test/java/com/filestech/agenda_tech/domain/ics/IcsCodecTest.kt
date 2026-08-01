package com.filestech.agenda_tech.domain.ics

import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.model.Weekday
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

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
}
