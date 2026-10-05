package com.filestech.agenda_tech.ui.screens.agenda

import app.cash.turbine.test
import com.filestech.agenda_tech.core.time.FakeDeviceZone
import com.filestech.agenda_tech.core.time.FarZones
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander
import com.filestech.agenda_tech.domain.usecase.FakeCalendarRepository
import com.filestech.agenda_tech.domain.usecase.FakeEventRepository
import com.filestech.agenda_tech.domain.usecase.ObserveOccurrencesInRangeUseCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import java.time.ZoneId

/** The agenda list files each event under a day; an all-day event belongs under its own date. */
class AgendaViewModelTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val dispatcher = StandardTestDispatcher()
    private val eventRepo = FakeEventRepository()
    private val calendarRepo = FakeCalendarRepository()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        calendarRepo.stored += Calendar(id = 1, name = "Personal")
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest
    @ValueSource(strings = [FarZones.AHEAD, FarZones.BEHIND])
    fun `an all-day event created in another time zone is filed under its own date`(elsewhereId: String) = runTest(dispatcher) {
        // Grouped by its stored start read on the phone's clock, it went under the day before when
        // created in a zone ahead of here.
        val day = LocalDate.now(zone).plusDays(10)
        val elsewhere = ZoneId.of(elsewhereId)
        eventRepo.rows[1] = Event(
            id = 1,
            calendarId = 1,
            title = "Holiday",
            startUtcMillis = day.atStartOfDay(elsewhere).toInstant().toEpochMilli(),
            endUtcMillis = day.plusDays(1).atStartOfDay(elsewhere).toInstant().toEpochMilli(),
            timeZoneId = elsewhere.id,
            allDay = true,
        )
        val vm = AgendaViewModel(
            ObserveOccurrencesInRangeUseCase(eventRepo, calendarRepo, RecurrenceExpander(), dispatcher),
            calendarRepo,
            FakeDeviceZone(zone),
        )

        vm.uiState.test {
            testScheduler.advanceUntilIdle()
            val days = expectMostRecentItem().days
            assertThat(days.map { it.date }).containsExactly(day)
            // A tap hands the editor the instant the occurrence is known by, not its place here.
            assertThat(days.single().items.single().occurrenceStartUtcMillis)
                .isEqualTo(eventRepo.rows.getValue(1).startUtcMillis)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an open agenda follows a change of time zone`() = runTest(dispatcher) {
        // 01:00 in Paris is 19:00 the day before in New York: the list must move the event there once
        // the phone is, not when the process happens to end.
        val paris = ZoneId.of("Europe/Paris")
        val deviceZone = FakeDeviceZone(paris)
        val day = LocalDate.now(paris).plusDays(10)
        val start = day.atTime(1, 0).atZone(paris).toInstant().toEpochMilli()
        eventRepo.rows[1] = Event(
            id = 1,
            calendarId = 1,
            title = "Early",
            startUtcMillis = start,
            endUtcMillis = start + 3_600_000,
            timeZoneId = paris.id,
        )
        val vm = AgendaViewModel(
            ObserveOccurrencesInRangeUseCase(eventRepo, calendarRepo, RecurrenceExpander(), dispatcher),
            calendarRepo,
            deviceZone,
        )

        vm.uiState.test {
            testScheduler.advanceUntilIdle()
            assertThat(expectMostRecentItem().days.map { it.date }).containsExactly(day)

            deviceZone.zone.value = ZoneId.of("America/New_York")
            testScheduler.advanceUntilIdle()

            assertThat(expectMostRecentItem().days.map { it.date }).containsExactly(day.minusDays(1))
            cancelAndIgnoreRemainingEvents()
        }
    }
}
