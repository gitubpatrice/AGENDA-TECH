package com.filestech.agenda_tech.ui.screens.timeline

import app.cash.turbine.test
import com.filestech.agenda_tech.core.time.FakeDeviceZone
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander
import com.filestech.agenda_tech.domain.usecase.FakeCalendarRepository
import com.filestech.agenda_tech.domain.usecase.FakeEventRepository
import com.filestech.agenda_tech.domain.usecase.FakeSettingsRepository
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The week and day views follow the phone while they are open: its time zone after a journey, and the
 * app's language for the first day of the week. Both were read once, when the view model was made.
 */
class TimelineFollowsPhoneTest {

    private val paris = ZoneId.of("Europe/Paris")
    private val dispatcher = StandardTestDispatcher()
    private val eventRepo = FakeEventRepository()
    private val calendarRepo = FakeCalendarRepository()
    private val deviceZone = FakeDeviceZone(paris)
    private val occurrences = ObserveOccurrencesInRangeUseCase(eventRepo, calendarRepo, RecurrenceExpander(), dispatcher)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        calendarRepo.stored += Calendar(id = 1, name = "Personal")
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the week starts on the day the screen's language starts it`() = runTest(dispatcher) {
        // First day set to "system": Sunday in American English, Monday in German.
        val vm = WeekViewModel(occurrences, calendarRepo, FakeSettingsRepository(), deviceZone)

        vm.uiState.test {
            vm.onLocaleChange(Locale.US)
            testScheduler.advanceUntilIdle()
            assertThat(expectMostRecentItem().weekStart.dayOfWeek).isEqualTo(DayOfWeek.SUNDAY)

            vm.onLocaleChange(Locale.GERMANY)
            testScheduler.advanceUntilIdle()
            assertThat(expectMostRecentItem().weekStart.dayOfWeek).isEqualTo(DayOfWeek.MONDAY)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an open day follows a change of time zone`() = runTest(dispatcher) {
        // 01:00 in Paris is 19:00 the day before in New York: once the phone is there, it leaves the day.
        val today = LocalDate.now(paris)
        val start = today.atTime(1, 0).atZone(paris).toInstant().toEpochMilli()
        eventRepo.rows[1] = Event(
            id = 1,
            calendarId = 1,
            title = "Early",
            startUtcMillis = start,
            endUtcMillis = start + 3_600_000,
            timeZoneId = paris.id,
        )
        val vm = DayViewModel(occurrences, calendarRepo, deviceZone)

        vm.uiState.test {
            testScheduler.advanceUntilIdle()
            assertThat(expectMostRecentItem().day.positioned.map { it.item.title }).containsExactly("Early")

            deviceZone.zone.value = ZoneId.of("America/New_York")
            testScheduler.advanceUntilIdle()

            assertThat(expectMostRecentItem().day.positioned).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
