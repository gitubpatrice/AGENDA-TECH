package com.filestech.agenda_tech.domain.recurrence

import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.reminder.ReminderScheduling
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Audit F5 — the pass budget is wired to **every** entry point of the expander, not just to [expand].
 *
 * `ExpansionBudget` was introduced by audit F8 with this reasoning in its own KDoc: the per-event scan
 * cap "does nothing about the total: a window that has to expand N events pays that cap N times over,
 * and the per-event bound says nothing about N. An import can raise N at will." That argument applies
 * unchanged to [RecurrenceExpander.nextOccurrenceStart] and [RecurrenceExpander.lastOccurrenceStartBefore],
 * which received no budget parameter at all — and those are the two the reminder scheduler and the
 * search box go through. Rescheduling after a reboot runs inside a broadcast measured in seconds; a
 * search runs on every keystroke.
 *
 * These tests use a deliberately tiny budget so the ceiling is observable without burning a real one.
 */
class ExpansionBudgetWiringTest {

    private val expander = RecurrenceExpander()

    @Test
    fun `nextOccurrenceStart stops once the shared budget is spent`() {
        // A daily series starting in 1970, asked for the next occurrence after 2026: reaching it costs
        // thousands of iterations. With three allowed, the answer must be "nothing found", not a value
        // obtained by ignoring the ceiling.
        val event = dailyFrom(1970)
        val budget = ExpansionBudget(maxIterations = 3)

        val found = expander.nextOccurrenceStart(event, afterUtcMillis = millis(2026, 1, 1), budget = budget)

        assertThat(found).isNull()
        assertThat(budget.isExhausted).isTrue()
    }

    @Test
    fun `lastOccurrenceStartBefore stops once the shared budget is spent`() {
        val event = dailyFrom(1970)
        val budget = ExpansionBudget(maxIterations = 3)

        val found =
            expander.lastOccurrenceStartBefore(event, beforeUtcMillis = millis(2026, 1, 1), budget = budget)

        // Only the first few occurrences of 1970 were ever generated, so the answer is one of those and
        // certainly not a 2025 date: the walk stopped where the ceiling said.
        assertThat(found).isNotNull()
        assertThat(found!!).isLessThan(millis(1971, 1, 1))
        assertThat(budget.isExhausted).isTrue()
    }

    @Test
    fun `one budget shared across several events is what bounds the whole pass`() {
        // The point F8 made, applied here: three events, one allowance. The third must find nothing
        // because the first two spent it — that is the difference between a per-event cap and a pass cap.
        val budget = ExpansionBudget(maxIterations = 4)
        val results = (1..3).map {
            expander.nextOccurrenceStart(dailyFrom(1970), millis(2026, 1, 1), budget = budget)
        }
        assertThat(results).containsExactly(null, null, null)
        assertThat(budget.spent).isAtMost(4)
    }

    @Test
    fun `a generous budget does not change the answer of a healthy series`() {
        // The ceiling must be invisible in normal use, or it would be a bug of its own.
        val event = dailyFrom(2026)
        val withBudget = expander.nextOccurrenceStart(event, millis(2026, 1, 5), budget = ExpansionBudget())
        val withoutBudget = expander.nextOccurrenceStart(event, millis(2026, 1, 5))
        assertThat(withBudget).isEqualTo(withoutBudget)
        assertThat(withBudget).isNotNull()
    }

    /**
     * Audit F16 — the reminder scheduler now passes the instants replaced by live per-occurrence
     * overrides, exactly as the calendar views and search do.
     *
     * It used to pass nothing and rely on the `EXDATE`s persisted on the master alone, which made it the
     * only reader with its own answer to "does this occurrence still exist".
     */
    @Test
    fun `computeNextFire skips an occurrence replaced by a live override`() {
        val event = dailyFrom(2026)
        val secondDay = millis(2026, 1, 2)

        val withoutExclusion = ReminderScheduling.computeNextFire(
            expander,
            event,
            minutesBefore = 0,
            earliestOccurrenceStartUtcMillis = secondDay,
        )
        assertThat(withoutExclusion?.occurrenceStartUtcMillis).isEqualTo(secondDay)

        val withExclusion = ReminderScheduling.computeNextFire(
            expander,
            event,
            minutesBefore = 0,
            earliestOccurrenceStartUtcMillis = secondDay,
            extraExcludedStartsUtcMillis = setOf(secondDay),
        )
        // The moved occurrence is skipped and the reminder lands on the next real one.
        assertThat(withExclusion?.occurrenceStartUtcMillis).isEqualTo(millis(2026, 1, 3))
    }

    @Test
    fun `computeNextFire honours the pass budget`() {
        val fire = ReminderScheduling.computeNextFire(
            expander,
            dailyFrom(1970),
            minutesBefore = 0,
            earliestOccurrenceStartUtcMillis = millis(2026, 1, 1),
            budget = ExpansionBudget(maxIterations = 3),
        )
        assertThat(fire).isNull()
    }

    private fun dailyFrom(year: Int): Event = Event(
        id = 1L,
        calendarId = 1L,
        title = "Consultation",
        startUtcMillis = millis(year, 1, 1),
        endUtcMillis = millis(year, 1, 1) + 3_600_000L,
        timeZoneId = "UTC",
        recurrence = RecurrenceRule(RecurrenceFreq.DAILY),
    )

    private fun millis(year: Int, month: Int, day: Int): Long =
        LocalDateTime.of(year, month, day, 9, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
}
