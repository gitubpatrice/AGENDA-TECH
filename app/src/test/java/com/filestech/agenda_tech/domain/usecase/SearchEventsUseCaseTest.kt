package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.model.Weekday
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SearchEventsUseCaseTest {

    private val zone = ZoneId.of("Europe/Paris")
    private fun at(y: Int, m: Int, d: Int, h: Int = 9): Long =
        LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    /** Pinned "now": Wednesday 15 July 2026, 12:00 Paris. */
    private val now = at(2026, 7, 15, 12)

    private val perso = Calendar(id = 1, name = "Perso", color = CalendarColor.GRAPE, isDefault = true)
    private val travail = Calendar(id = 2, name = "Travail", color = CalendarColor.BASIL, isVisible = false)

    private val eventRepo = FakeEventRepository()
    private val calendarRepo = FakeCalendarRepository()

    // Dispatchers.Unconfined rather than a TestDispatcher: the injected dispatcher only backs the
    // use case's flowOn, there is no virtual time to drive, and a standalone TestDispatcher would
    // carry its own scheduler and clash with runTest's.
    private val useCase = SearchEventsUseCase(
        eventRepo,
        calendarRepo,
        RecurrenceExpander(),
        Dispatchers.Unconfined,
    )

    private fun event(
        id: Long,
        title: String,
        start: Long,
        calendarId: Long = 1,
        description: String? = null,
        location: String? = null,
        city: String? = null,
        recurrence: RecurrenceRule? = null,
    ) = Event(
        id = id,
        calendarId = calendarId,
        title = title,
        description = description,
        location = location,
        city = city,
        startUtcMillis = start,
        endUtcMillis = start + 3_600_000,
        timeZoneId = zone.id,
        recurrence = recurrence,
    )

    private fun seed(vararg events: Event) {
        calendarRepo.stored += listOf(perso, travail)
        events.forEach { eventRepo.rows[it.id] = it }
    }

    private suspend fun search(query: String) = useCase(flowOf(query)) { now }.first()

    @Test
    fun `finds an accented title from an unaccented query`() = runTest {
        seed(event(10, "Réunion d'équipe", at(2026, 7, 20)))

        val hits = search("reunion")

        assertThat(hits).hasSize(1)
        assertThat(hits.single().event.title).isEqualTo("Réunion d'équipe")
    }

    @Test
    fun `searches description, location and city — not just the title`() = runTest {
        seed(
            event(10, "RDV", at(2026, 7, 20), description = "Apporter la carte vitale"),
            event(11, "Déjeuner", at(2026, 7, 21), location = "Chez Marcel"),
            event(12, "Formation", at(2026, 7, 22), city = "Avignon"),
        )

        assertThat(search("vitale").single().event.id).isEqualTo(10)
        assertThat(search("marcel").single().event.id).isEqualTo(11)
        assertThat(search("avignon").single().event.id).isEqualTo(12)
    }

    @Test
    fun `does not invent matches across two fields`() = runTest {
        // Concatenating title+city without a separator would make "reunice" a hit.
        seed(event(10, "Réu", at(2026, 7, 20), city = "Nice"))

        assertThat(search("reunice")).isEmpty()
        assertThat(search("reu")).hasSize(1)
    }

    @Test
    fun `an empty or blank query returns nothing, not everything`() = runTest {
        seed(event(10, "Réunion", at(2026, 7, 20)))

        assertThat(search("")).isEmpty()
        assertThat(search("   ")).isEmpty()
    }

    @Test
    fun `spans hidden calendars — silence would read as it does not exist`() = runTest {
        seed(event(10, "Point équipe", at(2026, 7, 20), calendarId = travail.id))

        val hits = search("point")

        assertThat(hits).hasSize(1)
        // The hit carries its calendar so the UI can say where it lives.
        assertThat(hits.single().calendar.name).isEqualTo("Travail")
        assertThat(hits.single().calendar.isVisible).isFalse()
    }

    @Test
    fun `orders upcoming soonest-first, then past most-recent-first`() = runTest {
        seed(
            event(10, "Dentiste", at(2026, 7, 20)), // upcoming, soon
            event(11, "Dentiste", at(2026, 9, 10)), // upcoming, later
            event(12, "Dentiste", at(2026, 3, 2)), // past, older
            event(13, "Dentiste", at(2026, 6, 30)), // past, recent
        )

        val hits = search("dentiste")

        assertThat(hits.map { it.event.id }).containsExactly(10L, 11L, 13L, 12L).inOrder()
        assertThat(hits.map { it.isUpcoming }).containsExactly(true, true, false, false).inOrder()
    }

    @Test
    fun `dates a running recurring series to its next occurrence, not its base start`() = runTest {
        // Created in January, still running: showing the base start would date it six months ago.
        seed(
            event(
                10, "Sport", at(2026, 1, 6, 18),
                recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY, byWeekdays = setOf(Weekday.TUESDAY)),
            ),
        )

        val hit = search("sport").single()

        assertThat(hit.isUpcoming).isTrue()
        // First Tuesday at 18:00 at or after Wednesday 15 July noon → Tuesday 21 July.
        assertThat(hit.occurrenceStartUtcMillis).isEqualTo(at(2026, 7, 21, 18))
    }

    @Test
    fun `dates a finished recurring series to its last occurrence`() = runTest {
        // Weekly on Tuesday from 6 January, 4 occurrences → last is Tuesday 27 January.
        seed(
            event(
                10, "Cours de piano", at(2026, 1, 6, 18),
                recurrence = RecurrenceRule(
                    freq = RecurrenceFreq.WEEKLY,
                    byWeekdays = setOf(Weekday.TUESDAY),
                    count = 4,
                ),
            ),
        )

        val hit = search("piano").single()

        assertThat(hit.isUpcoming).isFalse()
        assertThat(hit.occurrenceStartUtcMillis).isEqualTo(at(2026, 1, 27, 18))
    }

    @Test
    fun `drops an event whose every occurrence was cancelled — nothing truthful to date it with`() = runTest {
        seed(
            event(
                10, "Yoga", at(2026, 1, 6, 18),
                recurrence = RecurrenceRule(
                    freq = RecurrenceFreq.WEEKLY,
                    byWeekdays = setOf(Weekday.TUESDAY),
                    count = 2,
                    exDatesUtcMillis = listOf(at(2026, 1, 6, 18), at(2026, 1, 13, 18)),
                ),
            ),
        )

        assertThat(search("yoga")).isEmpty()
    }

    @Test
    fun `a master is dated past an occurrence a live override replaces, even without its EXDATE`() = runTest {
        // The editor normally writes the override AND adds its date to the master's EXDATEs, but those
        // are two separate writes. Search must not depend on the second one having landed: it reads
        // the live overrides, exactly like the calendar views do.
        val master = event(
            10, "Sport", at(2026, 1, 6, 18),
            recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY, byWeekdays = setOf(Weekday.TUESDAY)),
            // Note: no exDatesUtcMillis — this is the state after a crash between the two writes.
        )
        val movedOccurrence = at(2026, 7, 21, 18) // the next Tuesday, which the user moved
        val override = Event(
            id = 11,
            calendarId = 1,
            title = "Sport",
            startUtcMillis = at(2026, 7, 23, 19), // moved to Thursday 19:00
            endUtcMillis = at(2026, 7, 23, 20),
            timeZoneId = zone.id,
            recurrenceParentId = 10,
            originalStartUtcMillis = movedOccurrence,
        )
        seed(master, override)

        val hits = search("sport")

        // The master skips the replaced Tuesday and points at the following one...
        val masterHit = hits.single { it.event.id == 10L }
        assertThat(masterHit.occurrenceStartUtcMillis).isEqualTo(at(2026, 7, 28, 18))
        // ...and the moved occurrence shows itself, on its real date.
        val overrideHit = hits.single { it.event.id == 11L }
        assertThat(overrideHit.occurrenceStartUtcMillis).isEqualTo(at(2026, 7, 23, 19))
    }

    @Test
    fun `is case-insensitive and matches inside a word`() = runTest {
        seed(event(10, "Rendez-vous MÉDICAL", at(2026, 7, 20)))

        assertThat(search("medical")).hasSize(1)
        assertThat(search("MEDICAL")).hasSize(1)
        assertThat(search("dical")).hasSize(1)
    }

    @Test
    fun `an event whose calendar is missing is dropped rather than rendered broken`() = runTest {
        calendarRepo.stored += perso
        eventRepo.rows[10] = event(10, "Orphelin", at(2026, 7, 20), calendarId = 99)

        assertThat(search("orphelin")).isEmpty()
    }
}
