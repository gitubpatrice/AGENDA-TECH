package com.filestech.agenda_tech.domain.ics

import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.model.Weekday
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
        assertThat(roundTrip(event)).isEqualTo(event)
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
    }

    @Test
    fun `encode wraps events in a VCALENDAR envelope`() {
        val text = IcsCodec.encode(emptyList(), now)
        assertThat(text).contains("BEGIN:VCALENDAR")
        assertThat(text).contains("END:VCALENDAR")
    }
}
