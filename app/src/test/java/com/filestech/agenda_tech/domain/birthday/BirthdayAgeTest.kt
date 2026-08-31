package com.filestech.agenda_tech.domain.birthday

import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.EventKind
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The age shown beside a birthday is derived, never stored: the master event starts on the birth
 * date and the expander steps a year at a time from it. These tests pin the three cases where the
 * honest answer is "show nothing" rather than a number.
 */
class BirthdayAgeTest {

    private val zone: ZoneId = ZoneId.of("Europe/Paris")

    private fun startOf(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun birthday(year: Int, month: Int, day: Int) = Event(
        calendarId = 1,
        title = "Paul",
        startUtcMillis = startOf(year, month, day),
        // plusDays, not day + 1: a 29 February birthday would otherwise ask for a 30 February.
        endUtcMillis = LocalDate.of(year, month, day).plusDays(1)
            .atStartOfDay(zone).toInstant().toEpochMilli(),
        timeZoneId = zone.id,
        allDay = true,
        kind = EventKind.BIRTHDAY,
    )

    @Test
    fun `the age is the distance in years between the birth date and the occurrence`() {
        val event = birthday(1984, 3, 12)
        assertThat(BirthdayAge.of(event, startOf(2026, 3, 12), zone)).isEqualTo(42)
    }

    @Test
    fun `an ordinary event never carries an age`() {
        val event = birthday(1984, 3, 12).copy(kind = EventKind.NORMAL)
        assertThat(BirthdayAge.of(event, startOf(2026, 3, 12), zone)).isNull()
    }

    @Test
    fun `the birth year itself shows nothing rather than zero`() {
        // This is also what a user who does not know the year gets: they leave the proposed year, so
        // the first occurrence lands on it. "0 an" would be a claim; nothing is the truth.
        val event = birthday(2026, 3, 12)
        assertThat(BirthdayAge.of(event, startOf(2026, 3, 12), zone)).isNull()
    }

    @Test
    fun `an occurrence before the birth date shows nothing`() {
        // Reachable by editing the birth date of an existing series to a later year.
        val event = birthday(2030, 3, 12)
        assertThat(BirthdayAge.of(event, startOf(2026, 3, 12), zone)).isNull()
    }

    @Test
    fun `the age does not change with the zone it is read in`() {
        // The worst case for an all-day event: a 1 January birthday, whose midnight-Paris instants both
        // fall on 31 December in New York. Both the birth date and the occurrence shift by the same
        // year, so their difference does not — someone travelling does not age a year on landing.
        //
        // This holds only because an all-day row is anchored to one zone on BOTH sides, which is the
        // invariant DeviceEventMapper, IcsCodec and the editor each maintain separately. If one of them
        // ever anchored a birthday's start differently from its occurrences, this test is where it shows.
        val occurrence = LocalDate.of(2026, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val event = birthday(1984, 1, 1)

        val inParis = BirthdayAge.of(event, occurrence, zone)
        val inNewYork = BirthdayAge.of(event, occurrence, ZoneId.of("America/New_York"))
        val inTokyo = BirthdayAge.of(event, occurrence, ZoneId.of("Asia/Tokyo"))

        assertThat(inParis).isEqualTo(42)
        assertThat(inNewYork).isEqualTo(inParis)
        assertThat(inTokyo).isEqualTo(inParis)
    }

    @Test
    fun `an override of a birthday shows no age`() {
        // An override replaces one occurrence and starts on THAT date, so it carries no birth year.
        // Explicit rather than incidental: today the arithmetic would also return null (2026 - 2026),
        // but only because the editor rewrites an override's start — a coincidence, not a guarantee.
        val override = birthday(1984, 3, 12).copy(
            startUtcMillis = startOf(2026, 3, 12),
            endUtcMillis = startOf(2026, 3, 13),
            recurrenceParentId = 7L,
            originalStartUtcMillis = startOf(2026, 3, 12),
        )
        assertThat(override.isOverride).isTrue()
        assertThat(BirthdayAge.of(override, startOf(2026, 3, 12), zone)).isNull()

        // Even if a future change stopped rewriting the start, the answer stays "no number".
        val keepingBirthStart = override.copy(startUtcMillis = startOf(1984, 3, 12))
        assertThat(BirthdayAge.of(keepingBirthStart, startOf(2026, 3, 12), zone)).isNull()
    }

    @Test
    fun `a 29 February birthday is only ever celebrated in leap years`() {
        // Not a defect of this class but the contract it depends on: RecurrenceExpander emits a yearly
        // occurrence only when the day exists in that year (RFC 5545 ignores invalid dates), so the age
        // is never asked about a shifted 28 February — which would be a different number.
        val event = birthday(2000, 2, 29)
        assertThat(BirthdayAge.of(event, startOf(2024, 2, 29), zone)).isEqualTo(24)
    }
}
