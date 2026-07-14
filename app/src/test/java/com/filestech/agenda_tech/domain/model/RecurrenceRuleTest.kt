package com.filestech.agenda_tech.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RecurrenceRuleTest {

    @Test
    fun `interval below one is rejected`() {
        assertThrows<IllegalArgumentException> {
            RecurrenceRule(freq = RecurrenceFreq.DAILY, interval = 0)
        }
    }

    @Test
    fun `count and until are mutually exclusive`() {
        assertThrows<IllegalArgumentException> {
            RecurrenceRule(freq = RecurrenceFreq.WEEKLY, count = 5, untilUtcMillis = 1_000L)
        }
    }

    @Test
    fun `a rule with neither bound is infinite`() {
        val rule = RecurrenceRule(freq = RecurrenceFreq.MONTHLY)
        assertThat(rule.isInfinite).isTrue()
    }

    @Test
    fun `a bounded rule is not infinite`() {
        val rule = RecurrenceRule(freq = RecurrenceFreq.DAILY, count = 10)
        assertThat(rule.isInfinite).isFalse()
    }

    @Test
    fun `RecurrenceFreq fromRaw falls back to DAILY on an unknown value`() {
        assertThat(RecurrenceFreq.fromRaw(999)).isEqualTo(RecurrenceFreq.DAILY)
    }

    @Test
    fun `CalendarColor fromRaw falls back to the default on an unknown value`() {
        assertThat(CalendarColor.fromRaw(-1)).isEqualTo(CalendarColor.DEFAULT)
    }

    @Test
    fun `Weekday isoValue maps Monday to one and Sunday to seven`() {
        assertThat(Weekday.MONDAY.isoValue).isEqualTo(1)
        assertThat(Weekday.SUNDAY.isoValue).isEqualTo(7)
    }
}
