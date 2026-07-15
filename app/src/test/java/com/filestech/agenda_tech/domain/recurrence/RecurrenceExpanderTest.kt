package com.filestech.agenda_tech.domain.recurrence

import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.model.Weekday
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class RecurrenceExpanderTest {

    private val expander = RecurrenceExpander()

    // --- Non-recurring passthrough ------------------------------------------

    @Test
    fun `non-recurring event yields one occurrence when it overlaps the window`() {
        val event = singleEvent(UTC, at(2025, 6, 1, 9, 0), durationMinutes = 60)
        val occ = expander.expand(event, ms(UTC, at(2025, 6, 1, 0, 0)), ms(UTC, at(2025, 6, 2, 0, 0)))
        assertThat(occ).hasSize(1)
        assertThat(occ.single().startUtcMillis).isEqualTo(event.startUtcMillis)
    }

    @Test
    fun `non-recurring event outside the window yields nothing`() {
        val event = singleEvent(UTC, at(2025, 6, 10, 9, 0), durationMinutes = 60)
        val occ = expander.expand(event, ms(UTC, at(2025, 6, 1, 0, 0)), ms(UTC, at(2025, 6, 2, 0, 0)))
        assertThat(occ).isEmpty()
    }

    // --- DAILY ---------------------------------------------------------------

    @Test
    fun `daily rule generates one occurrence per day within the window`() {
        val event = recurringEvent(UTC, at(2025, 6, 1, 9, 0), 60, RecurrenceRule(RecurrenceFreq.DAILY))
        val occ = expander.expand(event, ms(UTC, at(2025, 6, 1, 0, 0)), ms(UTC, at(2025, 6, 4, 0, 0)))
        assertThat(startsLocal(UTC, occ)).containsExactly(
            at(2025, 6, 1, 9, 0),
            at(2025, 6, 2, 9, 0),
            at(2025, 6, 3, 9, 0),
        ).inOrder()
    }

    @Test
    fun `daily rule with interval skips days`() {
        val event = recurringEvent(UTC, at(2025, 6, 1, 9, 0), 60, RecurrenceRule(RecurrenceFreq.DAILY, interval = 2))
        val occ = expander.expand(event, ms(UTC, at(2025, 6, 1, 0, 0)), ms(UTC, at(2025, 6, 7, 0, 0)))
        assertThat(startsLocal(UTC, occ)).containsExactly(
            at(2025, 6, 1, 9, 0),
            at(2025, 6, 3, 9, 0),
            at(2025, 6, 5, 9, 0),
        ).inOrder()
    }

    // --- WEEKLY --------------------------------------------------------------

    @Test
    fun `weekly rule with BYDAY generates the listed weekdays`() {
        // 2025-06-02 is a Monday.
        val rule = RecurrenceRule(
            freq = RecurrenceFreq.WEEKLY,
            byWeekdays = setOf(Weekday.MONDAY, Weekday.WEDNESDAY, Weekday.FRIDAY),
        )
        val event = recurringEvent(UTC, at(2025, 6, 2, 9, 0), 60, rule)
        val occ = expander.expand(event, ms(UTC, at(2025, 6, 2, 0, 0)), ms(UTC, at(2025, 6, 9, 0, 0)))
        assertThat(startsLocal(UTC, occ)).containsExactly(
            at(2025, 6, 2, 9, 0), // Mon
            at(2025, 6, 4, 9, 0), // Wed
            at(2025, 6, 6, 9, 0), // Fri
        ).inOrder()
    }

    @Test
    fun `weekly rule with interval skips whole weeks`() {
        val rule = RecurrenceRule(
            freq = RecurrenceFreq.WEEKLY,
            interval = 2,
            byWeekdays = setOf(Weekday.MONDAY),
        )
        val event = recurringEvent(UTC, at(2025, 6, 2, 9, 0), 60, rule)
        val occ = expander.expand(event, ms(UTC, at(2025, 6, 2, 0, 0)), ms(UTC, at(2025, 7, 1, 0, 0)))
        assertThat(startsLocal(UTC, occ)).containsExactly(
            at(2025, 6, 2, 9, 0),
            at(2025, 6, 16, 9, 0),
            at(2025, 6, 30, 9, 0),
        ).inOrder()
    }

    // --- MONTHLY -------------------------------------------------------------

    @Test
    fun `monthly rule repeats on the same day of month`() {
        val event = recurringEvent(UTC, at(2025, 1, 15, 9, 0), 60, RecurrenceRule(RecurrenceFreq.MONTHLY))
        val occ = expander.expand(event, ms(UTC, at(2025, 1, 1, 0, 0)), ms(UTC, at(2025, 4, 1, 0, 0)))
        assertThat(startsLocal(UTC, occ)).containsExactly(
            at(2025, 1, 15, 9, 0),
            at(2025, 2, 15, 9, 0),
            at(2025, 3, 15, 9, 0),
        ).inOrder()
    }

    @Test
    fun `monthly rule skips months without the target day rather than clamping`() {
        // The 31st exists only in Jan, Mar, May... February must be skipped, not clamped to the 28th.
        val event = recurringEvent(UTC, at(2025, 1, 31, 9, 0), 60, RecurrenceRule(RecurrenceFreq.MONTHLY))
        val occ = expander.expand(event, ms(UTC, at(2025, 1, 1, 0, 0)), ms(UTC, at(2025, 5, 1, 0, 0)))
        assertThat(startsLocal(UTC, occ)).containsExactly(
            at(2025, 1, 31, 9, 0),
            at(2025, 3, 31, 9, 0),
        ).inOrder()
    }

    // --- YEARLY --------------------------------------------------------------

    @Test
    fun `yearly rule skips Feb 29 in common years`() {
        val event = recurringEvent(UTC, at(2024, 2, 29, 9, 0), 60, RecurrenceRule(RecurrenceFreq.YEARLY))
        val occ = expander.expand(event, ms(UTC, at(2024, 1, 1, 0, 0)), ms(UTC, at(2029, 1, 1, 0, 0)))
        assertThat(startsLocal(UTC, occ)).containsExactly(
            at(2024, 2, 29, 9, 0),
            at(2028, 2, 29, 9, 0),
        ).inOrder()
    }

    // --- COUNT / UNTIL -------------------------------------------------------

    @Test
    fun `count bounds the series length`() {
        val event = recurringEvent(UTC, at(2025, 6, 1, 9, 0), 60, RecurrenceRule(RecurrenceFreq.DAILY, count = 3))
        val occ = expander.expand(event, ms(UTC, at(2025, 6, 1, 0, 0)), ms(UTC, at(2025, 12, 1, 0, 0)))
        assertThat(startsLocal(UTC, occ)).containsExactly(
            at(2025, 6, 1, 9, 0),
            at(2025, 6, 2, 9, 0),
            at(2025, 6, 3, 9, 0),
        ).inOrder()
    }

    @Test
    fun `until is inclusive of an occurrence exactly on the boundary`() {
        val until = ms(UTC, at(2025, 6, 3, 9, 0))
        val event = recurringEvent(UTC, at(2025, 6, 1, 9, 0), 60, RecurrenceRule(RecurrenceFreq.DAILY, untilUtcMillis = until))
        val occ = expander.expand(event, ms(UTC, at(2025, 6, 1, 0, 0)), ms(UTC, at(2025, 12, 1, 0, 0)))
        assertThat(startsLocal(UTC, occ)).containsExactly(
            at(2025, 6, 1, 9, 0),
            at(2025, 6, 2, 9, 0),
            at(2025, 6, 3, 9, 0),
        ).inOrder()
    }

    // --- EXDATE --------------------------------------------------------------

    @Test
    fun `exdate removes an occurrence but it still consumes a count slot`() {
        // count = 3, but 06-02 is excluded → we expect 06-01 and 06-03 only (NOT 06-04),
        // proving the excluded instance still counted toward COUNT per RFC 5545.
        val exdate = ms(UTC, at(2025, 6, 2, 9, 0))
        val rule = RecurrenceRule(RecurrenceFreq.DAILY, count = 3, exDatesUtcMillis = listOf(exdate))
        val event = recurringEvent(UTC, at(2025, 6, 1, 9, 0), 60, rule)
        val occ = expander.expand(event, ms(UTC, at(2025, 6, 1, 0, 0)), ms(UTC, at(2025, 12, 1, 0, 0)))
        assertThat(startsLocal(UTC, occ)).containsExactly(
            at(2025, 6, 1, 9, 0),
            at(2025, 6, 3, 9, 0),
        ).inOrder()
    }

    // --- Time zone / DST -----------------------------------------------------

    @Test
    fun `daily rule keeps the local wall-clock time across the spring-forward DST transition`() {
        // Europe/Paris springs forward on 2025-03-30 (02:00 -> 03:00). A daily 09:00 event must
        // stay at 09:00 local on both sides — the UTC instant shifts by one hour.
        val zone = "Europe/Paris"
        val event = recurringEvent(zone, at(2025, 3, 29, 9, 0), 60, RecurrenceRule(RecurrenceFreq.DAILY))
        val occ = expander.expand(event, ms(zone, at(2025, 3, 29, 0, 0)), ms(zone, at(2025, 4, 1, 0, 0)))

        // Every occurrence reads 09:00 local.
        assertThat(startsLocal(zone, occ)).containsExactly(
            at(2025, 3, 29, 9, 0),
            at(2025, 3, 30, 9, 0),
            at(2025, 3, 31, 9, 0),
        ).inOrder()

        // 09:00 CET (UTC+1) then 09:00 CEST (UTC+2): the raw UTC delta is 23h, not 24h.
        val deltaMillis = occ[1].startUtcMillis - occ[0].startUtcMillis
        assertThat(deltaMillis).isEqualTo(23L * 60 * 60 * 1000)
    }

    // --- All-day -------------------------------------------------------------

    @Test
    fun `all-day daily rule preserves day boundaries`() {
        val rule = RecurrenceRule(RecurrenceFreq.DAILY, count = 2)
        val event = recurringEvent(UTC, at(2025, 6, 1, 0, 0), durationMinutes = 24 * 60, rule = rule, allDay = true)
        val occ = expander.expand(event, ms(UTC, at(2025, 6, 1, 0, 0)), ms(UTC, at(2025, 6, 10, 0, 0)))
        assertThat(startsLocal(UTC, occ)).containsExactly(
            at(2025, 6, 1, 0, 0),
            at(2025, 6, 2, 0, 0),
        ).inOrder()
        // Each all-day occurrence spans exactly 24h.
        occ.forEach { assertThat(it.endUtcMillis - it.startUtcMillis).isEqualTo(24L * 60 * 60 * 1000) }
    }

    // --- Window filtering ----------------------------------------------------

    @Test
    fun `only occurrences overlapping the window are returned`() {
        val event = recurringEvent(UTC, at(2025, 6, 1, 9, 0), 60, RecurrenceRule(RecurrenceFreq.DAILY))
        // Window covers just 06-05 and 06-06.
        val occ = expander.expand(event, ms(UTC, at(2025, 6, 5, 0, 0)), ms(UTC, at(2025, 6, 7, 0, 0)))
        assertThat(startsLocal(UTC, occ)).containsExactly(
            at(2025, 6, 5, 9, 0),
            at(2025, 6, 6, 9, 0),
        ).inOrder()
    }

    @Test
    fun `extra excluded starts are skipped like exdate (per-occurrence override)`() {
        val event = recurringEvent(UTC, at(2025, 6, 1, 9, 0), 60, RecurrenceRule(RecurrenceFreq.DAILY))
        val excluded = setOf(ms(UTC, at(2025, 6, 2, 9, 0)))
        val occ = expander.expand(
            event,
            ms(UTC, at(2025, 6, 1, 0, 0)),
            ms(UTC, at(2025, 6, 4, 0, 0)),
            excluded,
        )
        assertThat(startsLocal(UTC, occ)).containsExactly(
            at(2025, 6, 1, 9, 0),
            at(2025, 6, 3, 9, 0),
        ).inOrder()
    }

    // --- nextOccurrenceStart -------------------------------------------------

    @Test
    fun `nextOccurrenceStart returns a non-recurring event's start only when at or after the instant`() {
        val event = singleEvent(UTC, at(2025, 6, 10, 9, 0), 60)
        assertThat(nextStart(event, at(2025, 6, 1, 0, 0))).isEqualTo(ms(UTC, at(2025, 6, 10, 9, 0)))
        assertThat(nextStart(event, at(2025, 6, 20, 0, 0))).isNull()
    }

    @Test
    fun `nextOccurrenceStart finds the next daily occurrence at or after the instant`() {
        val event = recurringEvent(UTC, at(2025, 6, 1, 9, 0), 60, RecurrenceRule(RecurrenceFreq.DAILY))
        // After 2025-06-03 12:00, the next 09:00 occurrence is 2025-06-04.
        assertThat(nextStart(event, at(2025, 6, 3, 12, 0))).isEqualTo(ms(UTC, at(2025, 6, 4, 9, 0)))
    }

    @Test
    fun `nextOccurrenceStart returns null once a bounded series has ended`() {
        val event = recurringEvent(UTC, at(2025, 6, 1, 9, 0), 60, RecurrenceRule(RecurrenceFreq.DAILY, count = 3))
        // Series is 06-01, 06-02, 06-03; nothing at or after 06-04.
        assertThat(nextStart(event, at(2025, 6, 4, 0, 0))).isNull()
    }

    @Test
    fun `nextOccurrenceStart skips an excluded occurrence`() {
        val exdate = ms(UTC, at(2025, 6, 4, 9, 0))
        val rule = RecurrenceRule(RecurrenceFreq.DAILY, exDatesUtcMillis = listOf(exdate))
        val event = recurringEvent(UTC, at(2025, 6, 1, 9, 0), 60, rule)
        // 06-04 is excluded, so after 06-03 12:00 the next is 06-05.
        assertThat(nextStart(event, at(2025, 6, 3, 12, 0))).isEqualTo(ms(UTC, at(2025, 6, 5, 9, 0)))
    }

    // --- helpers -------------------------------------------------------------

    private companion object {
        const val UTC = "UTC"
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): LocalDateTime =
        LocalDateTime.of(year, month, day, hour, minute)

    private fun ms(zone: String, local: LocalDateTime): Long =
        local.atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    private fun singleEvent(zone: String, start: LocalDateTime, durationMinutes: Long): Event =
        buildEvent(zone, start, durationMinutes, recurrence = null, allDay = false)

    private fun recurringEvent(
        zone: String,
        start: LocalDateTime,
        durationMinutes: Long,
        rule: RecurrenceRule,
        allDay: Boolean = false,
    ): Event = buildEvent(zone, start, durationMinutes, rule, allDay)

    private fun buildEvent(
        zone: String,
        start: LocalDateTime,
        durationMinutes: Long,
        recurrence: RecurrenceRule?,
        allDay: Boolean,
    ): Event {
        val z = ZoneId.of(zone)
        return Event(
            calendarId = 1L,
            title = "Test",
            startUtcMillis = start.atZone(z).toInstant().toEpochMilli(),
            endUtcMillis = start.plusMinutes(durationMinutes).atZone(z).toInstant().toEpochMilli(),
            timeZoneId = zone,
            allDay = allDay,
            recurrence = recurrence,
        )
    }


    // --- lastOccurrenceStartBefore -------------------------------------------

    @Test
    fun `lastOccurrenceStartBefore returns a non-recurring event's start only when strictly before`() {
        val event = singleEvent(UTC, at(2025, 6, 10, 9, 0), 60)
        assertThat(lastStart(event, at(2025, 6, 20, 0, 0))).isEqualTo(ms(UTC, at(2025, 6, 10, 9, 0)))
        assertThat(lastStart(event, at(2025, 6, 1, 0, 0))).isNull()
    }

    @Test
    fun `lastOccurrenceStartBefore finds the last occurrence of an open-ended series`() {
        // The path with no COUNT and no UNTIL: nothing bounds the sequence except the instant asked
        // for. Left untested, a refactor could make this run to the scan cap — or forever.
        val event = recurringEvent(UTC, at(2025, 6, 1, 9, 0), 60, RecurrenceRule(RecurrenceFreq.DAILY))

        assertThat(lastStart(event, at(2025, 6, 4, 12, 0))).isEqualTo(ms(UTC, at(2025, 6, 4, 9, 0)))
    }

    @Test
    fun `lastOccurrenceStartBefore returns the final occurrence of a series that has ended`() {
        val event = recurringEvent(
            UTC,
            at(2025, 6, 1, 9, 0),
            60,
            RecurrenceRule(RecurrenceFreq.DAILY, count = 3),
        )

        // Series is 1, 2, 3 June — long after it ended, the answer stays the 3rd.
        assertThat(lastStart(event, at(2026, 1, 1, 0, 0))).isEqualTo(ms(UTC, at(2025, 6, 3, 9, 0)))
    }

    @Test
    fun `lastOccurrenceStartBefore excludes an occurrence starting exactly at the instant`() {
        // "Before" is strict — the mirror of nextOccurrenceStart, which is inclusive. Without this
        // boundary being pinned, an occurrence could be reported as both next and last.
        val event = recurringEvent(UTC, at(2025, 6, 1, 9, 0), 60, RecurrenceRule(RecurrenceFreq.DAILY))
        val exactly = at(2025, 6, 4, 9, 0)

        assertThat(lastStart(event, exactly)).isEqualTo(ms(UTC, at(2025, 6, 3, 9, 0)))
        assertThat(nextStart(event, exactly)).isEqualTo(ms(UTC, exactly))
    }

    @Test
    fun `lastOccurrenceStartBefore skips an excluded occurrence`() {
        val event = recurringEvent(
            UTC,
            at(2025, 6, 1, 9, 0),
            60,
            RecurrenceRule(
                RecurrenceFreq.DAILY,
                exDatesUtcMillis = listOf(ms(UTC, at(2025, 6, 4, 9, 0))),
            ),
        )

        assertThat(lastStart(event, at(2025, 6, 4, 12, 0))).isEqualTo(ms(UTC, at(2025, 6, 3, 9, 0)))
    }

    @Test
    fun `lastOccurrenceStartBefore returns null when every occurrence was excluded`() {
        val event = recurringEvent(
            UTC,
            at(2025, 6, 1, 9, 0),
            60,
            RecurrenceRule(
                RecurrenceFreq.DAILY,
                count = 2,
                exDatesUtcMillis = listOf(ms(UTC, at(2025, 6, 1, 9, 0)), ms(UTC, at(2025, 6, 2, 9, 0))),
            ),
        )

        assertThat(lastStart(event, at(2025, 7, 1, 0, 0))).isNull()
    }

    @Test
    fun `lastOccurrenceStartBefore returns null before the series begins`() {
        val event = recurringEvent(UTC, at(2025, 6, 1, 9, 0), 60, RecurrenceRule(RecurrenceFreq.DAILY))

        assertThat(lastStart(event, at(2025, 5, 1, 0, 0))).isNull()
    }

    private fun startsLocal(zone: String, occurrences: List<EventOccurrence>): List<LocalDateTime> =
        occurrences.map { Instant.ofEpochMilli(it.startUtcMillis).atZone(ZoneId.of(zone)).toLocalDateTime() }

    private fun nextStart(event: Event, after: LocalDateTime): Long? =
        expander.nextOccurrenceStart(event, ms(UTC, after))

    private fun lastStart(event: Event, before: LocalDateTime): Long? =
        expander.lastOccurrenceStartBefore(event, ms(UTC, before))
}
