package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.domain.ImportLimits
import com.filestech.agenda_tech.domain.model.Calendar
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

private const val ZONE = "Europe/Paris"

private fun ics(vararg vevents: String): String =
    buildString {
        appendLine("BEGIN:VCALENDAR")
        appendLine("VERSION:2.0")
        vevents.forEach { appendLine(it) }
        appendLine("END:VCALENDAR")
    }

private fun vevent(uid: String?, summary: String, start: String = "20251121T090000Z"): String =
    buildString {
        appendLine("BEGIN:VEVENT")
        uid?.let { appendLine("UID:$it") }
        appendLine("SUMMARY:$summary")
        appendLine("DTSTART:$start")
        appendLine("DTEND:20251121T100000Z")
        append("END:VEVENT")
    }

/**
 * Orchestration of the `.ics` import (calendar targeting + idempotence). The parsing itself is
 * covered by `IcsCodecTest`; this pins the behaviour the user actually feels — re-importing the same
 * file must not duplicate their agenda.
 */
class ImportEventsUseCaseTest {

    private val calendars = FakeCalendarRepository()
    private val events = FakeEventRepository()
    private val useCase = ImportEventsUseCase(eventRepository = events, calendarRepository = calendars)

    @Test
    fun `imports into the default calendar`() = runTest {
        calendars.upsert(Calendar(name = "Autre", isDefault = false))
        val defaultId = calendars.upsert(Calendar(name = "Perso", isDefault = true))

        val count = useCase(ics(vevent("u1", "RDV")), ZONE)

        assertThat(count).isEqualTo(1)
        assertThat(events.rows.values.single().calendarId).isEqualTo(defaultId)
    }

    @Test
    fun `does nothing when no calendar exists yet`() = runTest {
        val count = useCase(ics(vevent("u1", "RDV")), ZONE)

        assertThat(count).isEqualTo(0)
        assertThat(events.rows).isEmpty()
    }

    @Test
    fun `re-importing the same file does not duplicate events`() = runTest {
        calendars.upsert(Calendar(name = "Perso", isDefault = true))
        val file = ics(vevent("u1", "RDV"), vevent("u2", "Dentiste"))

        useCase(file, ZONE)
        useCase(file, ZONE)

        assertThat(events.rows).hasSize(2)
    }

    @Test
    fun `re-importing an edited event updates it in place`() = runTest {
        calendars.upsert(Calendar(name = "Perso", isDefault = true))
        useCase(ics(vevent("u1", "Avant")), ZONE)
        val idBefore = events.rows.keys.single()

        useCase(ics(vevent("u1", "Après")), ZONE)

        assertThat(events.rows).hasSize(1)
        assertThat(events.rows.keys.single()).isEqualTo(idBefore)
        assertThat(events.rows.values.single().title).isEqualTo("Après")
    }

    @Test
    fun `an empty or invalid document imports nothing`() = runTest {
        calendars.upsert(Calendar(name = "Perso", isDefault = true))

        assertThat(useCase("", ZONE)).isEqualTo(0)
        assertThat(useCase("pas du tout un ics", ZONE)).isEqualTo(0)
        assertThat(events.rows).isEmpty()
    }

    @Test
    fun `an unparseable time zone falls back instead of failing`() = runTest {
        calendars.upsert(Calendar(name = "Perso", isDefault = true))

        val count = useCase(ics(vevent("u1", "RDV")), "Pas/Une/Zone")

        assertThat(count).isEqualTo(1)
    }

    // --- Audit S12 : un plafond sur le NOMBRE, pas seulement sur les octets ---

    @Test
    fun `a file over the event ceiling is refused whole, before a single row is written`() = runTest {
        calendars.stored += Calendar(id = 1, name = "Perso", isDefault = true)
        val tooMany = ics(
            *Array(ImportLimits.MAX_EVENTS + 1) { vevent("u$it", "RDV $it") },
        )

        val thrown = assertThrows(ImportEventsUseCase.TooManyEvents::class.java) {
            runBlocking { useCase(tooMany, ZONE) }
        }

        assertThat(thrown.found).isEqualTo(ImportLimits.MAX_EVENTS + 1)
        // The whole point of refusing rather than truncating: nothing was written, so the user sees a
        // refusal they can act on instead of a partial agenda that looks complete.
        assertThat(events.rows).isEmpty()
    }

    @Test
    fun `a file exactly at the ceiling is accepted`() = runTest {
        // The boundary is where a cap gets it wrong, and refusing a legitimate file is the failure
        // mode audit F4 already produced once on this very screen.
        calendars.stored += Calendar(id = 1, name = "Perso", isDefault = true)
        val atLimit = ics(*Array(ImportLimits.MAX_EVENTS) { vevent("u$it", "RDV $it") })

        assertThat(useCase(atLimit, ZONE)).isEqualTo(ImportLimits.MAX_EVENTS)
    }
}
