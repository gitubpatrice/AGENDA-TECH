package com.filestech.agenda_tech.domain.reminder

import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderSchedulingTest {

    private val expander = RecurrenceExpander()
    private val zone = "UTC"

    private fun ms(local: LocalDateTime): Long =
        local.atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    private fun dailyEventAt9() = Event(
        calendarId = 1L,
        title = "Standup",
        startUtcMillis = ms(LocalDateTime.of(2025, 6, 1, 9, 0)),
        endUtcMillis = ms(LocalDateTime.of(2025, 6, 1, 9, 30)),
        timeZoneId = zone,
        recurrence = RecurrenceRule(RecurrenceFreq.DAILY),
    )

    @Test
    fun `initial fire is in the future and 10 minutes before the next occurrence`() {
        val event = dailyEventAt9()
        val now = ms(LocalDateTime.of(2025, 6, 3, 8, 55)) // 5 min before today's 09:00
        val minutesBefore = 10

        val fire = ReminderScheduling.computeNextFire(
            expander = expander,
            event = event,
            minutesBefore = minutesBefore,
            earliestOccurrenceStartUtcMillis = ReminderScheduling.initialEarliestStart(now, minutesBefore),
        )

        // Today's 09:00 is only 5 min away (< 10 before), so the next valid occurrence is tomorrow.
        assertThat(fire).isNotNull()
        assertThat(fire!!.occurrenceStartUtcMillis).isEqualTo(ms(LocalDateTime.of(2025, 6, 4, 9, 0)))
        assertThat(fire.fireAtUtcMillis).isEqualTo(ms(LocalDateTime.of(2025, 6, 4, 8, 50)))
        assertThat(fire.fireAtUtcMillis).isAtLeast(now)
    }

    @Test
    fun `rescheduling after a fire rolls forward to the following occurrence`() {
        val event = dailyEventAt9()
        val firedOccurrence = ms(LocalDateTime.of(2025, 6, 4, 9, 0))

        val fire = ReminderScheduling.computeNextFire(
            expander = expander,
            event = event,
            minutesBefore = 10,
            earliestOccurrenceStartUtcMillis = ReminderScheduling.nextEarliestStart(firedOccurrence),
        )

        assertThat(fire!!.occurrenceStartUtcMillis).isEqualTo(ms(LocalDateTime.of(2025, 6, 5, 9, 0)))
    }

    @Test
    fun `a bounded series stops producing fires once exhausted`() {
        val event = Event(
            calendarId = 1L,
            title = "Standup",
            startUtcMillis = ms(LocalDateTime.of(2025, 6, 1, 9, 0)),
            endUtcMillis = ms(LocalDateTime.of(2025, 6, 1, 9, 30)),
            timeZoneId = zone,
            recurrence = RecurrenceRule(RecurrenceFreq.DAILY, count = 2), // 06-01, 06-02
        )
        val afterSeries = ms(LocalDateTime.of(2025, 6, 3, 0, 0))
        val fire = ReminderScheduling.computeNextFire(expander, event, 10, afterSeries)
        assertThat(fire).isNull()
    }

    @Test
    fun `a past non-recurring event produces no fire`() {
        val event = Event(
            calendarId = 1L,
            title = "One-off",
            startUtcMillis = ms(LocalDateTime.of(2025, 6, 1, 9, 0)),
            endUtcMillis = ms(LocalDateTime.of(2025, 6, 1, 10, 0)),
            timeZoneId = zone,
        )
        val now = ms(LocalDateTime.of(2025, 6, 2, 0, 0))
        val fire = ReminderScheduling.computeNextFire(
            expander, event, 10, ReminderScheduling.initialEarliestStart(now, 10),
        )
        assertThat(fire).isNull()
    }
}
