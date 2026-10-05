package com.filestech.agenda_tech.domain.reminder

import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander
import com.filestech.agenda_tech.domain.recurrence.shownStartUtcMillis
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
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
            earliestOccurrenceStartUtcMillis = ReminderScheduling.nextEarliestStart(
                firedOccurrenceStartUtcMillis = firedOccurrence,
                nowUtcMillis = firedOccurrence,
                minutesBefore = 10,
            ),
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

    // --- All-day events created in another zone ------------------------------------------------

    private val paris = ZoneId.of("Europe/Paris")
    private val newYork = ZoneId.of("America/New_York")

    private fun midnight(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun allDayIn(zone: ZoneId, date: LocalDate, recurrence: RecurrenceRule? = null) = Event(
        calendarId = 1L,
        title = "Holiday",
        startUtcMillis = midnight(date, zone),
        endUtcMillis = midnight(date.plusDays(1), zone),
        timeZoneId = zone.id,
        allDay = true,
        recurrence = recurrence,
    )

    @Test
    fun `an all-day reminder rings at the phone's midnight, not at the one of the zone it was made in`() {
        // Made in Paris, phone in New York: "at the start" used to ring at 18:00 the day before.
        val day = LocalDate.of(2026, 7, 20)
        val event = allDayIn(paris, day)
        val now = LocalDateTime.of(2026, 7, 19, 12, 0).atZone(newYork).toInstant().toEpochMilli()

        val fire = ReminderScheduling.computeNextFire(
            expander, event, 0, ReminderScheduling.initialEarliestStart(now, 0), zone = newYork,
        )

        assertThat(fire!!.fireAtUtcMillis).isEqualTo(midnight(day, newYork))
        // The alarm still names the occurrence by its stored instant.
        assertThat(fire.occurrenceStartUtcMillis).isEqualTo(midnight(day, paris))
    }

    @Test
    fun `an all-day reminder still due on the phone is not skipped`() {
        // 22:00 in New York on the 19th: the 20th has already begun in Paris, so its stored start is
        // past, but its midnight here is two hours away. Searching by stored start skipped it.
        val event = allDayIn(paris, LocalDate.of(2026, 7, 1), RecurrenceRule(RecurrenceFreq.DAILY))
        val now = LocalDateTime.of(2026, 7, 19, 22, 0).atZone(newYork).toInstant().toEpochMilli()

        val fire = ReminderScheduling.computeNextFire(
            expander, event, 0, ReminderScheduling.initialEarliestStart(now, 0), zone = newYork,
        )

        assertThat(fire!!.fireAtUtcMillis).isEqualTo(midnight(LocalDate.of(2026, 7, 20), newYork))
    }

    @Test
    fun `after an all-day reminder rings, the next one is the following day, east or west`() {
        val fired = LocalDate.of(2026, 7, 20)
        for ((madeIn, phone) in listOf(paris to newYork, newYork to paris)) {
            val event = allDayIn(madeIn, LocalDate.of(2026, 7, 1), RecurrenceRule(RecurrenceFreq.DAILY))
            val firedShown = event.shownStartUtcMillis(midnight(fired, madeIn), phone)

            // As ReminderScheduler does it: the fired occurrence placed on the phone's calendar.
            val fire = ReminderScheduling.computeNextFire(
                expander,
                event,
                0,
                ReminderScheduling.nextEarliestStart(firedShown, nowUtcMillis = firedShown, minutesBefore = 0),
                zone = phone,
            )

            assertThat(fire!!.occurrenceStartUtcMillis).isEqualTo(midnight(fired.plusDays(1), madeIn))
            assertThat(fire.fireAtUtcMillis).isEqualTo(midnight(fired.plusDays(1), phone))
        }
    }
}
