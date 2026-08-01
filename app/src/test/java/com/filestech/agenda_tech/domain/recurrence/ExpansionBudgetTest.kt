package com.filestech.agenda_tech.domain.recurrence

import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Audit F8 — the expander caps the iterations spent on one event; these pin the cap that spans a
 * whole pass, which is the one an import can defeat by adding events instead of worsening a rule.
 */
class ExpansionBudgetTest {

    private val expander = RecurrenceExpander()
    private val utc = ZoneId.of("UTC")

    @Test
    fun `a shared budget is spent across events, not reset for each one`() {
        // Two events expanded under one budget: the second sees only what the first left.
        val budget = ExpansionBudget(maxIterations = 30)
        val event = dailyEvent(at(2025, 1, 1, 9, 0))
        val window = ms(at(2025, 1, 1, 0, 0)) to ms(at(2026, 1, 1, 0, 0))

        val first = expander.expand(event, window.first, window.second, budget = budget)
        val second = expander.expand(event, window.first, window.second, budget = budget)

        assertThat(first).hasSize(30)
        assertThat(second).isEmpty()
        assertThat(budget.isExhausted).isTrue()
    }

    @Test
    fun `without a budget the per-event behaviour is unchanged`() {
        // The parameter is opt-in: the reminder scheduler and the tests that predate it must expand
        // exactly as before, or the bound would silently truncate legitimate series.
        val event = dailyEvent(at(2025, 1, 1, 9, 0))

        val occurrences = expander.expand(event, ms(at(2025, 1, 1, 0, 0)), ms(at(2025, 2, 1, 0, 0)))

        assertThat(occurrences).hasSize(31)
    }

    @Test
    fun `an exhausted budget truncates the pass instead of failing it`() {
        // Truncating is the whole point: a hostile agenda must degrade the view, never throw on the
        // render path — that was the failure mode of F1/F5/F7.
        val budget = ExpansionBudget(maxIterations = 5)
        val event = dailyEvent(at(2025, 1, 1, 9, 0))

        val occurrences = expander.expand(event, ms(at(2025, 1, 1, 0, 0)), ms(at(2026, 1, 1, 0, 0)), budget = budget)

        assertThat(occurrences).hasSize(5)
        assertThat(budget.spent).isEqualTo(5)
    }

    @Test
    fun `a budget refuses a nonsensical size rather than silently expanding nothing`() {
        assertThrows<IllegalArgumentException> { ExpansionBudget(maxIterations = 0) }
    }

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi)

    private fun ms(local: LocalDateTime): Long = local.atZone(utc).toInstant().toEpochMilli()

    private fun dailyEvent(start: LocalDateTime) = Event(
        id = 1,
        calendarId = 1,
        title = "Quotidien",
        startUtcMillis = ms(start),
        endUtcMillis = ms(start.plusHours(1)),
        timeZoneId = "UTC",
        recurrence = RecurrenceRule(RecurrenceFreq.DAILY),
    )
}
