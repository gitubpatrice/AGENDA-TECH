package com.filestech.agenda_tech.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * `excluding` is what both "delete this occurrence" and "move this occurrence" do to a master's rule,
 * and the list it builds is exported verbatim to `.ics` — a duplicate or a lost entry leaves another
 * calendar showing an occurrence the user already cancelled.
 */
class RecurrenceRuleExcludingTest {

    private val rule = RecurrenceRule(freq = RecurrenceFreq.WEEKLY)

    @Test
    fun `excluding adds the instant`() {
        assertThat(rule.excluding(1_000L).exDatesUtcMillis).containsExactly(1_000L)
    }

    @Test
    fun `excluding is idempotent — a re-save cannot grow duplicates`() {
        val once = rule.excluding(1_000L)
        val twice = once.excluding(1_000L)

        assertThat(twice.exDatesUtcMillis).containsExactly(1_000L)
        // Returns the very same instance, which is what lets the caller skip a pointless write.
        assertThat(twice).isSameInstanceAs(once)
    }

    @Test
    fun `excluding keeps the instants already there`() {
        val result = rule.excluding(1_000L).excluding(2_000L).excluding(3_000L)

        assertThat(result.exDatesUtcMillis).containsExactly(1_000L, 2_000L, 3_000L).inOrder()
    }

    @Test
    fun `excluding changes nothing else about the rule`() {
        val rich = RecurrenceRule(
            freq = RecurrenceFreq.WEEKLY,
            interval = 2,
            byWeekdays = setOf(Weekday.MONDAY, Weekday.THURSDAY),
            count = 12,
        )

        val result = rich.excluding(1_000L)

        assertThat(result).isEqualTo(rich.copy(exDatesUtcMillis = listOf(1_000L)))
    }
}
