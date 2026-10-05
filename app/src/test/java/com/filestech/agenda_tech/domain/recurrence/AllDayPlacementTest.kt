package com.filestech.agenda_tech.domain.recurrence

import com.filestech.agenda_tech.domain.model.Event
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Where an occurrence lands on the phone's calendar. An all-day event's instants are the midnights of
 * the zone it was created in; on a phone set to another zone it must still cover its own dates, from the
 * phone's midnight to the phone's midnight.
 */
class AllDayPlacementTest {

    private val paris = ZoneId.of("Europe/Paris")
    private val newYork = ZoneId.of("America/New_York")

    private fun midnight(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun allDay(first: LocalDate, days: Long, zone: ZoneId, zoneId: String = zone.id): EventOccurrence {
        val event = Event(
            calendarId = 1,
            title = "Holiday",
            startUtcMillis = midnight(first, zone),
            endUtcMillis = midnight(first.plusDays(days), zone),
            timeZoneId = zoneId,
            allDay = true,
        )
        return EventOccurrence(event, event.startUtcMillis, event.endUtcMillis)
    }

    @Test
    fun `an all-day event created in UTC covers its own date in Paris and in New York`() {
        val day = LocalDate.of(2026, 10, 14)
        val occurrence = allDay(day, 1, ZoneOffset.UTC, zoneId = "UTC")

        for (phone in listOf(paris, newYork)) {
            assertThat(occurrence.shownStartUtcMillis(phone)).isEqualTo(midnight(day, phone))
            assertThat(occurrence.shownEndUtcMillis(phone)).isEqualTo(midnight(day.plusDays(1), phone))
        }
    }

    @Test
    fun `the widest gap between two zones still lands on the same date`() {
        // UTC+14 to UTC−11: 25 hours apart, so the stored day and the phone's do not even overlap.
        val day = LocalDate.of(2026, 10, 14)
        val kiritimati = ZoneId.of("Pacific/Kiritimati")
        val pagoPago = ZoneId.of("Pacific/Pago_Pago")
        val occurrence = allDay(day, 1, kiritimati)

        assertThat(occurrence.shownStartUtcMillis(pagoPago)).isEqualTo(midnight(day, pagoPago))
        assertThat(occurrence.shownEndUtcMillis(pagoPago)).isEqualTo(midnight(day.plusDays(1), pagoPago))
        assertThat(occurrence.shownStartUtcMillis(pagoPago) - occurrence.startUtcMillis)
            .isAtMost(ALL_DAY_PLACEMENT_BOUND_MILLIS)
    }

    @Test
    fun `an all-day event over three days covers the same three dates elsewhere`() {
        val first = LocalDate.of(2026, 8, 3)
        val occurrence = allDay(first, 3, paris)

        assertThat(occurrence.shownStartUtcMillis(newYork)).isEqualTo(midnight(first, newYork))
        assertThat(occurrence.shownEndUtcMillis(newYork)).isEqualTo(midnight(first.plusDays(3), newYork))
    }

    @Test
    fun `a day shortened by daylight saving keeps its date on both sides`() {
        // 29 March 2026 lasts 23 hours in Paris, 8 March 2026 lasts 23 hours in New York.
        val parisDst = LocalDate.of(2026, 3, 29)
        val fromParis = allDay(parisDst, 1, paris)
        assertThat(fromParis.shownStartUtcMillis(newYork)).isEqualTo(midnight(parisDst, newYork))
        assertThat(fromParis.shownEndUtcMillis(newYork)).isEqualTo(midnight(parisDst.plusDays(1), newYork))

        val newYorkDst = LocalDate.of(2026, 3, 8)
        val fromNewYork = allDay(newYorkDst, 1, newYork)
        assertThat(fromNewYork.shownStartUtcMillis(paris)).isEqualTo(midnight(newYorkDst, paris))
        assertThat(fromNewYork.shownEndUtcMillis(paris)).isEqualTo(midnight(newYorkDst.plusDays(1), paris))
    }

    @Test
    fun `a stored end that is not a midnight still covers the day it falls in`() {
        val day = LocalDate.of(2026, 10, 14)
        val base = allDay(day, 1, paris)
        val endingAtNoon = base.copy(endUtcMillis = day.plusDays(1).atTime(12, 0).atZone(paris).toInstant().toEpochMilli())
        val endingAtStart = base.copy(endUtcMillis = base.startUtcMillis)

        assertThat(endingAtNoon.shownEndUtcMillis(newYork)).isEqualTo(midnight(day.plusDays(2), newYork))
        // An all-day occurrence always covers at least its first date.
        assertThat(endingAtStart.shownEndUtcMillis(newYork)).isEqualTo(midnight(day.plusDays(1), newYork))
    }

    @Test
    fun `a stored zone nothing can read is read as UTC, as the expander does`() {
        val day = LocalDate.of(2026, 10, 14)
        val occurrence = allDay(day, 1, ZoneOffset.UTC, zoneId = "Not/AZone")

        assertThat(occurrence.event.dateZone(paris)).isEqualTo(ZoneOffset.UTC)
        assertThat(occurrence.shownStartUtcMillis(paris)).isEqualTo(midnight(day, paris))
    }

    @Test
    fun `a timed event keeps its instants and is dated on the phone's clock`() {
        val start = LocalDateTime.of(2026, 10, 14, 23, 30).atZone(paris).toInstant().toEpochMilli()
        val event = Event(
            calendarId = 1,
            title = "Flight",
            startUtcMillis = start,
            endUtcMillis = start + 3_600_000,
            timeZoneId = "Asia/Tokyo",
        )
        val occurrence = EventOccurrence(event, event.startUtcMillis, event.endUtcMillis)

        assertThat(event.dateZone(newYork)).isEqualTo(newYork)
        assertThat(occurrence.shownStartUtcMillis(newYork)).isEqualTo(occurrence.startUtcMillis)
        assertThat(occurrence.shownEndUtcMillis(newYork)).isEqualTo(occurrence.endUtcMillis)
    }

    @Test
    fun `an all-day event's dates are read in its own zone`() {
        val occurrence = allDay(LocalDate.of(2026, 10, 14), 1, ZoneId.of("Asia/Tokyo"))

        assertThat(occurrence.event.dateZone(newYork)).isEqualTo(ZoneId.of("Asia/Tokyo"))
    }
}
