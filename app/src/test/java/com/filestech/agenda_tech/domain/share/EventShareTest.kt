package com.filestech.agenda_tech.domain.share

import com.filestech.agenda_tech.domain.ics.IcsCodec
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class EventShareTest {

    private val paris: ZoneId = ZoneId.of("Europe/Paris")
    private val french: Locale = Locale.FRANCE

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0, zone: ZoneId = paris): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun midnight(y: Int, m: Int, d: Int, zone: ZoneId): Long =
        LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun event(
        start: Long = at(2026, 10, 13, 9),
        end: Long = at(2026, 10, 13, 10),
        allDay: Boolean = false,
        zone: ZoneId = paris,
        location: String? = null,
        address: String? = null,
        postalCode: String? = null,
        city: String? = null,
        gps: String? = null,
        description: String? = null,
        recurrence: RecurrenceRule? = null,
        id: Long = 10,
        parentId: Long? = null,
    ) = Event(
        id = id,
        calendarId = 1,
        title = "Dentiste",
        description = description,
        location = location,
        address = address,
        postalCode = postalCode,
        city = city,
        gpsCoordinates = gps,
        startUtcMillis = start,
        endUtcMillis = end,
        timeZoneId = zone.id,
        allDay = allDay,
        recurrence = recurrence,
        recurrenceParentId = parentId,
        originalStartUtcMillis = parentId?.let { start },
        // As the editor hands it over: given an identity before it is written (withIcsIdentities).
        sourceUid = "ics:event-$id@example.com",
    )

    private fun text(event: Event, start: Long = event.startUtcMillis, end: Long = event.endUtcMillis) =
        EventShare.text(ShareSource(event, start, end), paris, french, title = event.title, allDayLabel = "Toute la journée")

    @Test
    fun `a full event reads as title, day and hours, place, then the notes`() {
        val shared = text(
            event(
                location = "Cabinet du Dr Martin",
                address = "12 rue des Lices",
                postalCode = "84000",
                city = "Avignon",
                description = "  Apporter la carte vitale.  ",
            ),
        )

        assertThat(shared).isEqualTo(
            """
            Dentiste
            Mardi 13 octobre 2026, 09:00 – 10:00
            Cabinet du Dr Martin, 12 rue des Lices, 84000 Avignon

            Apporter la carte vitale.
            """.trimIndent(),
        )
        // Plain spaces only: the line may be searched by the reader's maps app.
        assertThat(shared).doesNotContain(Char(0x00A0).toString())
    }

    @Test
    fun `an event with nothing but a title and hours gives two lines`() {
        assertThat(text(event())).isEqualTo("Dentiste\nMardi 13 octobre 2026, 09:00 – 10:00")
    }

    @Test
    fun `an event over two days names both days`() {
        val shared = text(event(start = at(2026, 10, 13, 22), end = at(2026, 10, 14, 2)))

        assertThat(shared.lines()[1]).isEqualTo("Mardi 13 octobre 2026, 22:00 – mercredi 14 octobre 2026, 02:00")
    }

    @Test
    fun `the occurrence opened is the one shared, not the series' first`() {
        val series = event(recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY))

        val shared = text(series, start = at(2026, 10, 20, 9), end = at(2026, 10, 20, 10))

        assertThat(shared.lines()[1]).isEqualTo("Mardi 20 octobre 2026, 09:00 – 10:00")
    }

    @Test
    fun `an all-day event gives its date, read in its own zone, and says all day`() {
        // Created in Tokyo: its midnights fall in the afternoon of the day before in Paris. Read on the
        // phone's clock, it would be shared one day early.
        val tokyo = ZoneId.of("Asia/Tokyo")
        val holiday = event(
            start = midnight(2026, 10, 13, tokyo),
            end = midnight(2026, 10, 14, tokyo),
            allDay = true,
            zone = tokyo,
        )

        assertThat(text(holiday).lines()[1]).isEqualTo("Mardi 13 octobre 2026 · Toute la journée")
    }

    @Test
    fun `an all-day event over several days gives its first and last dates`() {
        val holidays = event(start = midnight(2026, 10, 13, paris), end = midnight(2026, 10, 16, paris), allDay = true)

        assertThat(text(holidays).lines()[1])
            .isEqualTo("Mardi 13 octobre 2026 – jeudi 15 octobre 2026 · Toute la journée")
    }

    @Test
    fun `coordinates are the place when there is no other`() {
        assertThat(text(event(gps = "44.0512, 5.0489")).lines()).contains("44.0512, 5.0489")
        assertThat(text(event(gps = "Marseille")).lines()).hasSize(2)
    }

    @Test
    fun `the shared ics carries the full place, where the agenda's export keeps the name alone`() {
        val shared = EventShare.ics(
            event(location = "Cabinet", address = "12 rue des Lices", postalCode = "84000", city = "Avignon"),
            overrides = emptyList(),
            nowUtcMillis = 0,
        )

        assertThat(unfolded(shared)).contains("LOCATION:Cabinet\\, 12 rue des Lices\\, 84000 Avignon")
    }

    @Test
    fun `a shared series carries its moved occurrences`() {
        val series = event(recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY))
        val moved = event(id = 20, parentId = 10, start = at(2026, 10, 21, 15), end = at(2026, 10, 21, 16))

        val shared = EventShare.ics(series, listOf(moved), nowUtcMillis = 0)

        assertThat(Regex("BEGIN:VEVENT").findAll(shared).count()).isEqualTo(2)
        assertThat(shared).contains("RRULE:")
        // Read back by the app's own importer: two events, the series and the moved one.
        assertThat(IcsCodec.decode(shared, paris)).hasSize(2)
    }

    @Test
    fun `a file name is the title with nothing that could name a path`() {
        assertThat(EventShare.fileStem("RDV / dentiste")).isEqualTo("RDV dentiste")
        assertThat(EventShare.fileStem("../../etc/passwd")).isEqualTo("etcpasswd")
        assertThat(EventShare.fileStem("Réunion d'équipe")).isEqualTo("Réunion déquipe")
        assertThat(EventShare.fileStem("???")).isEqualTo("event")
        assertThat(EventShare.fileStem("x".repeat(100))).hasLength(EventShare.MAX_FILE_STEM)
    }

    /** RFC 5545 folds long lines; the test reads them unfolded, as any calendar does. */
    private fun unfolded(ics: String) = ics.replace("\r\n ", "")
}
