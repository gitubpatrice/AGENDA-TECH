package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.domain.device.DeviceEventMapper
import com.filestech.agenda_tech.domain.ics.IcsCodec
import com.filestech.agenda_tech.domain.ics.toIcsEvent
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.share.EventShare
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

private const val ZONE = "Europe/Paris"
private const val PERSO = 1L
private const val TRAVAIL = 2L
private const val HOUR = 3_600_000L
private val START = Instant.parse("2026-11-03T08:00:00Z").toEpochMilli()
private val NOW = Instant.parse("2026-10-22T12:00:00Z").toEpochMilli()

/**
 * The agenda's own `.ics`, brought back — what a user does to keep a laptop and the phone in step
 * (question by e-mail, 2026-10-22). Export, then import that very file: the agenda must come back as it
 * was, not doubled.
 *
 * Up to 1.3.0 an event created on the phone left with the UID `row-<id>@filestech` and nothing on the
 * phone answered to it, so the import added a copy — and the copy left with the same UID next time, so
 * every round trip added one more. The UID was also the same on every phone, which is why the fix cannot
 * simply read `row-12` back as "my row 12": a file shared by someone else would overwrite an unrelated
 * event (see `a file shared from another phone never overwrites an event here`).
 */
class IcsRoundTripTest {

    private val calendars = FakeCalendarRepository()
    private val events = FakeEventRepository()
    private val export = ExportEventsUseCase(events)
    private val import = ImportEventsUseCase(eventRepository = events, calendarRepository = calendars)

    @BeforeEach
    fun calendars() {
        calendars.stored += Calendar(id = PERSO, name = "Perso", isDefault = true)
        calendars.stored += Calendar(id = TRAVAIL, name = "Travail", isDefault = false)
    }

    private fun event(
        title: String = "Dentiste",
        calendarId: Long = PERSO,
        start: Long = START,
        recurrence: RecurrenceRule? = null,
    ) = Event(
        calendarId = calendarId,
        title = title,
        startUtcMillis = start,
        endUtcMillis = start + HOUR,
        timeZoneId = ZONE,
        recurrence = recurrence,
    )

    private suspend fun roundTrip() {
        import(export(NOW).ics, ZONE)
    }

    @Test
    fun `an event created here comes back once, not twice`() = runTest {
        val id = events.upsert(event())

        roundTrip()

        assertThat(events.rows.keys).containsExactly(id)
    }

    @Test
    fun `round trips do not grow the agenda`() = runTest {
        events.upsert(event("Dentiste"))
        events.upsert(event("Réunion", calendarId = TRAVAIL, start = START + 24 * HOUR))
        val before = events.rows.keys.toSet()

        repeat(3) { roundTrip() }

        assertThat(events.rows.keys).isEqualTo(before)
    }

    @Test
    fun `an event edited on the computer comes back updated, in place`() = runTest {
        val id = events.upsert(event("Dentiste"))
        val edited = export(NOW).ics.replace("SUMMARY:Dentiste", "SUMMARY:Dentiste reporté")

        import(edited, ZONE)

        assertThat(events.rows.keys).containsExactly(id)
        assertThat(events.rows.getValue(id).title).isEqualTo("Dentiste reporté")
    }

    @Test
    fun `the round trip keeps what only the phone knows - calendar, colour, address`() = runTest {
        // None of these travel in the .ics: an update that rebuilt the row from the file would move the
        // event to the default calendar and drop the colour.
        val id = events.upsert(
            event("Réunion", calendarId = TRAVAIL).copy(
                colorOverride = CalendarColor.TANGERINE,
                address = "12 rue des Lices",
                city = "Avignon",
            ),
        )

        roundTrip()

        val back = events.rows.getValue(id)
        assertThat(events.rows).hasSize(1)
        assertThat(back.calendarId).isEqualTo(TRAVAIL)
        assertThat(back.colorOverride).isEqualTo(CalendarColor.TANGERINE)
        assertThat(back.address).isEqualTo("12 rue des Lices")
        assertThat(back.city).isEqualTo("Avignon")
    }

    @Test
    fun `a moved occurrence comes back as part of its series`() = runTest {
        val masterId = events.upsert(event("Sport", recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY)))
        val occurrence = START + 7 * 24 * HOUR
        val overrideId = events.upsertOverrideAtomic(
            event("Sport déplacé", start = occurrence + 2 * HOUR).copy(
                recurrenceParentId = masterId,
                originalStartUtcMillis = occurrence,
            ),
            masterId = masterId,
            originalStartUtcMillis = occurrence,
        )

        roundTrip()

        assertThat(events.rows.keys).containsExactly(masterId, overrideId)
        val override = events.rows.getValue(overrideId)
        assertThat(override.recurrenceParentId).isEqualTo(masterId)
        assertThat(override.originalStartUtcMillis).isEqualTo(occurrence)
        assertThat(events.rows.getValue(masterId).recurrence?.exDatesUtcMillis).containsExactly(occurrence)
    }

    @Test
    fun `an event imported under a UID without a domain survives our own export`() = runTest {
        // The export appends "@filestech" to a UID that has no domain, so the file says `abc123@filestech`
        // where the row remembers `abc123`. Both spellings must find it.
        val original = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:abc123
            SUMMARY:Concert
            DTSTART:20261121T190000Z
            DTEND:20261121T210000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        import(original, ZONE)
        val id = events.rows.keys.single()

        roundTrip()
        import(original, ZONE)

        assertThat(events.rows.keys).containsExactly(id)
    }

    @Test
    fun `rows sharing a UID come back each to its own`() = runTest {
        // A series and its moved occurrence, imported from another calendar: one UID, two rows (the codec
        // does not read RECURRENCE-ID). The agenda lists them by start time, the import pairs them by id.
        import(
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:serie@example.com
            SUMMARY:Cours déplacé
            DTSTART:20261128T140000Z
            DTEND:20261128T150000Z
            END:VEVENT
            BEGIN:VEVENT
            UID:serie@example.com
            SUMMARY:Cours
            DTSTART:20261121T090000Z
            DTEND:20261121T100000Z
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
            ZONE,
        )
        val (moved, plain) = events.rows.keys.sorted()
        events.rows[moved] = events.rows.getValue(moved).copy(colorOverride = CalendarColor.TANGERINE)
        events.rows[plain] = events.rows.getValue(plain).copy(colorOverride = CalendarColor.BASIL)

        roundTrip()

        assertThat(events.rows.keys).containsExactly(moved, plain)
        assertThat(events.rows.getValue(moved).title).isEqualTo("Cours déplacé")
        assertThat(events.rows.getValue(moved).colorOverride).isEqualTo(CalendarColor.TANGERINE)
        assertThat(events.rows.getValue(plain).title).isEqualTo("Cours")
        assertThat(events.rows.getValue(plain).colorOverride).isEqualTo(CalendarColor.BASIL)
    }

    @Test
    fun `a file shared from another phone never overwrites an event here`() = runTest {
        // Up to 1.3.0, "Share as .ics" wrote `row-<id>@filestech` on every phone: the event 100 of a
        // friend's agenda carries the very UID this phone's event 100 used to leave with.
        val mine = events.upsert(event("Dentiste"))
        roundTrip()
        val theirs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:row-$mine@filestech
            SUMMARY:Anniversaire de Léa
            DTSTART:20261205T180000Z
            DTEND:20261205T220000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        import(theirs, ZONE)

        assertThat(events.rows).hasSize(2)
        assertThat(events.rows.getValue(mine).title).isEqualTo("Dentiste")
    }

    @Test
    fun `a copy of a device event is not taken for another phone's`() = runTest {
        // Its uid is the provider's sync id — an Exchange one reads `2:5` — or its row number on this
        // phone: nothing makes either unique across phones, and a friend's file can carry the same.
        val mine = events.upsert(event("Kiné", calendarId = TRAVAIL).copy(sourceUid = "2:5"))
        val local = events.upsert(event("Ostéo", calendarId = TRAVAIL).copy(sourceUid = DeviceEventMapper.rowIdUid(42)))
        val theirs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:${IcsCodec.uidAsWritten("2:5")}
            SUMMARY:Anniversaire de Léa
            DTSTART:20261205T180000Z
            DTEND:20261205T220000Z
            END:VEVENT
            BEGIN:VEVENT
            UID:${IcsCodec.uidAsWritten(DeviceEventMapper.rowIdUid(42))}
            SUMMARY:Anniversaire de Léa
            DTSTART:20261205T180000Z
            DTEND:20261205T220000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        import(theirs, ZONE)

        assertThat(events.rows).hasSize(4)
        assertThat(events.rows.getValue(mine).title).isEqualTo("Kiné")
        assertThat(events.rows.getValue(local).title).isEqualTo("Ostéo")
    }

    @Test
    fun `a share that comes back keeps the place as it is here`() = runTest {
        // "Share as .ics" writes the whole place line as LOCATION. The friend moves the appointment and
        // sends it back: the row must keep its place name, not take "name, street, town" beside the
        // address fields that line was made from — and grow by one copy of them per trip.
        val id = events.upsert(
            event("Dentiste").copy(location = "Cabinet", address = "12 rue des Lices", postalCode = "84000", city = "Avignon"),
        )
        val sent = EventShare.ics(events.withIcsIdentities(listOf(events.rows.getValue(id))).single(), emptyList(), NOW)

        import(sent.replace("SUMMARY:Dentiste", "SUMMARY:Dentiste reporté"), ZONE)

        val back = events.rows.getValue(id)
        assertThat(events.rows).hasSize(1)
        assertThat(back.title).isEqualTo("Dentiste reporté")
        assertThat(back.location).isEqualTo("Cabinet")
        assertThat(back.address).isEqualTo("12 rue des Lices")
    }

    @Test
    fun `a long description survives the round trip whole`() = runTest {
        // The import cuts a text at 4 000 characters, the editor does not: an update in place would
        // otherwise shorten the very event it came from.
        val long = "Ordre du jour. ".repeat(400)
        val id = events.upsert(event().copy(description = long))

        roundTrip()

        assertThat(events.rows.getValue(id).description).isEqualTo(long)
    }

    @Test
    fun `every event leaves with a UID of its own, not one another phone also writes`() = runTest {
        events.upsert(event("Dentiste"))
        events.upsert(event("Réunion"))

        val uids = Regex("^UID:(.+)$", RegexOption.MULTILINE)
            .findAll(export(NOW).ics.replace("\r\n", "\n"))
            .map { it.groupValues[1] }
            .toList()

        assertThat(uids).hasSize(2)
        assertThat(uids.toSet()).hasSize(2)
        uids.forEach { assertThat(it).doesNotMatch("row-\\d+@filestech") }
    }

    @Test
    fun `an event with no identity yet cannot be written into a file`() {
        // Its fallback used to be `row-<id>`: whatever writes a file goes through withIcsIdentities first.
        assertThrows(IllegalStateException::class.java) { event().copy(id = 5).toIcsEvent() }
    }

    @Test
    fun `an exported event keeps its UID from one export to the next`() = runTest {
        events.upsert(event("Dentiste"))

        val first = export(NOW).ics.lines().single { it.startsWith("UID:") }
        val second = export(NOW + HOUR).ics.lines().single { it.startsWith("UID:") }

        assertThat(second).isEqualTo(first)
    }
}
