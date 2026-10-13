package com.filestech.agenda_tech.ui.screens.timeline

import app.cash.turbine.test
import com.filestech.agenda_tech.core.time.FakeDeviceZone
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander
import com.filestech.agenda_tech.domain.usecase.FakeCalendarRepository
import com.filestech.agenda_tech.domain.usecase.FakeEventRepository
import com.filestech.agenda_tech.domain.usecase.FakeSettingsRepository
import com.filestech.agenda_tech.domain.usecase.GatedEventRepository
import com.filestech.agenda_tech.domain.usecase.ObserveHolidaysUseCase
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
import java.time.LocalDate
import java.time.ZoneId

/**
 * The day and week views are drawn from the window that was read, never from the date just asked for
 * (external review, 2026-10-13): the next day or week showed for an instant with the previous one's
 * events and holidays — its own not read yet — before they arrived.
 */
class TimelineWindowTest {

    private val paris = ZoneId.of("Europe/Paris")
    private val dispatcher = StandardTestDispatcher()
    private val eventRepo = FakeEventRepository()
    private val events = GatedEventRepository(eventRepo)
    private val calendarRepo = FakeCalendarRepository()
    private val deviceZone = FakeDeviceZone(paris)
    private val occurrences = ObserveOccurrencesInRangeUseCase(events, calendarRepo, RecurrenceExpander(), dispatcher)
    private val today = LocalDate.now(paris)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        calendarRepo.stored += Calendar(id = 1, name = "Personal")
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun seed(id: Long, title: String, date: LocalDate) {
        val start = date.atTime(9, 0).atZone(paris).toInstant().toEpochMilli()
        eventRepo.rows[id] = Event(
            id = id,
            calendarId = 1,
            title = title,
            startUtcMillis = start,
            endUtcMillis = start + 3_600_000,
            timeZoneId = paris.id,
        )
    }

    private fun DayTimelineData.titles() = positioned.map { it.item.title }

    @Test
    fun `the next day shows once its own events are read, not before`() = runTest(dispatcher) {
        seed(1, "Today", today)
        seed(2, "Tomorrow", today.plusDays(1))
        val vm = DayViewModel(occurrences, ObserveHolidaysUseCase(FakeSettingsRepository()), calendarRepo, deviceZone)

        vm.uiState.test {
            testScheduler.advanceUntilIdle()
            assertThat(expectMostRecentItem().day.titles()).containsExactly("Today")

            vm.onNextDay()
            testScheduler.advanceUntilIdle()
            expectNoEvents()

            events.gate.complete(Unit)
            testScheduler.advanceUntilIdle()
            val day = expectMostRecentItem().day
            assertThat(day.date).isEqualTo(today.plusDays(1))
            assertThat(day.titles()).containsExactly("Tomorrow")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the next week shows once its own events are read, not before`() = runTest(dispatcher) {
        seed(1, "Next week", today.plusWeeks(1))
        val settings = FakeSettingsRepository()
        val vm = WeekViewModel(occurrences, ObserveHolidaysUseCase(settings), calendarRepo, settings, deviceZone)

        vm.uiState.test {
            testScheduler.advanceUntilIdle()
            val shown = expectMostRecentItem().weekStart

            vm.onNextWeek()
            testScheduler.advanceUntilIdle()
            expectNoEvents()

            events.gate.complete(Unit)
            testScheduler.advanceUntilIdle()
            val week = expectMostRecentItem()
            assertThat(week.weekStart).isEqualTo(shown.plusWeeks(1))
            assertThat(week.days.flatMap { it.titles() }).containsExactly("Next week")
            cancelAndIgnoreRemainingEvents()
        }
    }
}
