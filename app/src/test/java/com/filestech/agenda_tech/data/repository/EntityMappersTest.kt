package com.filestech.agenda_tech.data.repository

import com.filestech.agenda_tech.data.local.db.entity.EventEntity
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
}
