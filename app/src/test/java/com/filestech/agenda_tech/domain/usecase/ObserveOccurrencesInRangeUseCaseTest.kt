package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.recurrence.EventOccurrence
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander
import com.filestech.agenda_tech.domain.repository.EventRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The query behind every calendar view, the widget included, for an all-day event created in a zone
 * 25 hours from the phone's: its stored day and the phone's day of the same date do not even overlap.
 */
class ObserveOccurrencesInRangeUseCaseTest {

    private val phone = ZoneId.of("Pacific/Kiritimati") // UTC+14
    private val elsewhere = ZoneId.of("Pacific/Pago_Pago") // UTC−11
    private val day = LocalDate.of(2026, 10, 14)

    private val dispatcher = StandardTestDispatcher()
    private val eventRepo = FakeEventRepository()
    private val calendarRepo = FakeCalendarRepository().apply { stored += Calendar(id = 1, name = "Personal") }

    /** Reads by window the way `EventDao.observeForExpansion` does, which the plain fake does not. */
    private val windowed = object : EventRepository by eventRepo {
        override fun observeForExpansion(windowStartUtcMillis: Long, windowEndUtcMillis: Long): Flow<List<Event>> =
            flowOf(
                eventRepo.rows.values.filter {
                    it.startUtcMillis < windowEndUtcMillis &&
                        (it.recurrence != null || it.endUtcMillis > windowStartUtcMillis)
                },
            )
    }

    private val useCase = ObserveOccurrencesInRangeUseCase(windowed, calendarRepo, RecurrenceExpander(), dispatcher)

    private fun midnight(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun seedAllDay(id: Long, date: LocalDate, recurrence: RecurrenceRule? = null) {
        eventRepo.rows[id] = Event(
            id = id,
            calendarId = 1,
            title = "Holiday $id",
            startUtcMillis = midnight(date, elsewhere),
            endUtcMillis = midnight(date.plusDays(1), elsewhere),
            timeZoneId = elsewhere.id,
            allDay = true,
            recurrence = recurrence,
        )
    }

    private suspend fun occurrencesOn(date: LocalDate): List<EventOccurrence> =
        useCase(midnight(date, phone), midnight(date.plusDays(1), phone), phone).first()

    @Test
    fun `an all-day event created far away is found on its own date, and only there`() = runTest(dispatcher) {
        seedAllDay(1, day)

        assertThat(occurrencesOn(day.minusDays(1))).isEmpty()
        assertThat(occurrencesOn(day).map { it.event.id }).containsExactly(1L)
        assertThat(occurrencesOn(day.plusDays(1))).isEmpty()
    }

    @Test
    fun `an occurrence keeps the instant it is known by`() = runTest(dispatcher) {
        // An EXDATE, an override and a reminder name it by its stored start; only its place moves.
        seedAllDay(1, day)

        assertThat(occurrencesOn(day).single().startUtcMillis).isEqualTo(midnight(day, elsewhere))
    }

    @Test
    fun `a weekly all-day series created far away falls on its weekday`() = runTest(dispatcher) {
        seedAllDay(1, day, RecurrenceRule(RecurrenceFreq.WEEKLY))
        val weekLater = day.plusWeeks(1)

        val inWeek = useCase(midnight(day.plusDays(1), phone), midnight(weekLater.plusDays(1), phone), phone).first()

        assertThat(inWeek.map { it.startUtcMillis }).containsExactly(midnight(weekLater, elsewhere))
    }

    @Test
    fun `occurrences come sorted by where they begin on the phone`() = runTest(dispatcher) {
        // Stored, the event created far away starts eleven hours after the phone's own: by place, it is
        // the earlier of the two days.
        seedAllDay(1, day)
        eventRepo.rows[2] = Event(
            id = 2,
            calendarId = 1,
            title = "Next day, here",
            startUtcMillis = midnight(day.plusDays(1), phone),
            endUtcMillis = midnight(day.plusDays(2), phone),
            timeZoneId = phone.id,
            allDay = true,
        )

        val twoDays = useCase(midnight(day, phone), midnight(day.plusDays(2), phone), phone).first()

        assertThat(twoDays.map { it.event.id }).containsExactly(1L, 2L).inOrder()
    }
}
