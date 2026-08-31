package com.filestech.agenda_tech.data.repository

import com.filestech.agenda_tech.data.local.db.AgendaEnumConverters
import com.filestech.agenda_tech.data.local.db.entity.EventEntity
import com.filestech.agenda_tech.domain.model.EventKind
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Audit F1 — the clamp on the way out of the database is the one that cannot be dropped.
 *
 * The import paths were hardened so no new poisoned row can be written, but a build shipped before
 * that fix could already have stored one. Reading it back without a clamp would hit the `require` now
 * guarding [RecurrenceRule], so the crash would only move from the recurrence expander to the mapper
 * — still on the render path of every view, still with no way for the user to reach the event.
 */
class EntityMappersTest {

    @Test
    fun `an out-of-range interval already stored is healed on read, not thrown on`() {
        val poisoned = eventEntity(rruleInterval = 1_000_000_000)

        val event = poisoned.toDomain()

        assertThat(event.recurrence?.interval).isEqualTo(RecurrenceRule.MAX_INTERVAL)
        assertThat(event.title).isEqualTo("Piégé")
    }

    @Test
    fun `a zero interval from a corrupted row is raised to 1 rather than rejected`() {
        // The column is a plain Int with no CHECK constraint, so 0 is representable; the domain
        // forbids it. Clamping keeps the row readable instead of making the event unreachable.
        assertThat(eventEntity(rruleInterval = 0).toDomain().recurrence?.interval).isEqualTo(1)
    }

    @Test
    fun `an interval within range is passed through untouched`() {
        assertThat(eventEntity(rruleInterval = 3).toDomain().recurrence?.interval).isEqualTo(3)
    }

    private fun eventEntity(rruleInterval: Int) = EventEntity(
        id = 1,
        calendarId = 1,
        title = "Piégé",
        description = null,
        location = null,
        startUtcMillis = 1_800_000_000_000,
        endUtcMillis = 1_800_003_600_000,
        timeZoneId = "Europe/Paris",
        allDay = false,
        rruleFreq = RecurrenceFreq.YEARLY,
        rruleInterval = rruleInterval,
        rruleByWeekdays = "",
        rruleCount = null,
        rruleUntilUtcMillis = null,
        rruleExDates = "",
        recurrenceParentId = null,
        originalStartUtcMillis = null,
        colorOverride = null,
        createdAt = 0,
        updatedAt = 0,
    )

    @Test
    fun `the event kind survives both directions of the mapper`() {
        val birthday = eventEntity(rruleInterval = 1).copy(kind = EventKind.BIRTHDAY)

        assertThat(birthday.toDomain().kind).isEqualTo(EventKind.BIRTHDAY)
        assertThat(birthday.toDomain().toEntity(createdAt = 0, updatedAt = 0).kind)
            .isEqualTo(EventKind.BIRTHDAY)
    }

    @Test
    fun `the raw 0 that migration 6 to 7 writes reads back as an ordinary event`() {
        // Goes through the TypeConverter, which is what actually turns the column into the enum.
        // Asserting on a Kotlin-constructed EventEntity instead would only re-state its own default
        // and would still pass if `eventKindFromRaw` were broken. Signalled by the gpt-5.2 review of
        // 2026-08-31, which is exactly the "test that does not test what it claims" it was asked for.
        val converters = AgendaEnumConverters()

        assertThat(converters.eventKindFromRaw(0)).isEqualTo(EventKind.NORMAL)
        assertThat(converters.eventKindFromRaw(1)).isEqualTo(EventKind.BIRTHDAY)
        // A value written by a newer build must not make an existing row unreadable.
        assertThat(converters.eventKindFromRaw(99)).isEqualTo(EventKind.NORMAL)
        assertThat(converters.eventKindToRaw(EventKind.BIRTHDAY)).isEqualTo(1)
    }
}
