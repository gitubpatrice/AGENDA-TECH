package com.filestech.agenda_tech.ui.screens.month

import app.cash.turbine.test
import com.filestech.agenda_tech.core.time.FakeDeviceZone
import com.filestech.agenda_tech.core.time.FarZones
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.repository.EventRepository
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander
import com.filestech.agenda_tech.domain.settings.AppSettings
import com.filestech.agenda_tech.domain.settings.MonthDisplay
import com.filestech.agenda_tech.domain.settings.WeekStart
import com.filestech.agenda_tech.domain.usecase.FakeCalendarRepository
import com.filestech.agenda_tech.domain.usecase.FakeEventRepository
import com.filestech.agenda_tech.domain.usecase.FakeSettingsRepository
import com.filestech.agenda_tech.domain.usecase.ObserveOccurrencesInRangeUseCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/**
 * What the month view puts in each day. The three displays draw the same list per day — dots, titles,
 * rows — so it is the list that is tested here, along with the neighbouring months read in advance.
 */
class MonthCellsTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val dispatcher = StandardTestDispatcher()
    private val eventRepo = FakeEventRepository()
    private val calendarRepo = FakeCalendarRepository()
    private val deviceZone = FakeDeviceZone(zone)

    // The view model opens on the current month; the events are placed relative to it.
    private val month: YearMonth = YearMonth.now(zone)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // Only the events of visible calendars are expanded: without one, every day would read empty and
        // each assertion below would pass or fail for that reason alone.
        calendarRepo.stored += Calendar(id = 1, name = "Personal")
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(settings: FakeSettingsRepository = FakeSettingsRepository()) = MonthViewModel(
        observeOccurrences = ObserveOccurrencesInRangeUseCase(eventRepo, calendarRepo, RecurrenceExpander(), dispatcher),
        calendarRepository = calendarRepo,
        eventRepository = eventRepo,
        settingsRepository = settings,
        deviceZone = deviceZone,
    )

    private fun millis(date: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun seed(id: Long, title: String, start: Long, end: Long, allDay: Boolean = false) {
        eventRepo.rows[id] = Event(
            id = id,
            calendarId = 1,
            title = title,
            startUtcMillis = start,
            endUtcMillis = end,
            timeZoneId = zone.id,
            allDay = allDay,
        )
    }

    private fun seedAllDay(id: Long, title: String, date: LocalDate) =
        seed(id, title, millis(date, 0), date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), allDay = true)

    // A WhileSubscribed StateFlow hands back its initial value when read without a collector.
    private suspend fun MonthViewModel.state(scheduler: TestCoroutineScheduler): MonthUiState {
        var value: MonthUiState? = null
        uiState.test {
            scheduler.advanceUntilIdle()
            value = expectMostRecentItem()
            cancelAndIgnoreRemainingEvents()
        }
        return value!!
    }

    /** An event repository whose range query never emits — a database still opening. */
    private class SilentEventRepository(delegate: EventRepository) : EventRepository by delegate {
        override fun observeForExpansion(windowStartUtcMillis: Long, windowEndUtcMillis: Long): Flow<List<Event>> =
            emptyFlow<List<Event>>().onStart { awaitCancellation() }
    }

    private fun MonthUiState.cell(date: LocalDate): DayCellData =
        pages.getValue(YearMonth.from(date)).flatten().first { it.date == date && it.isInMonth }

    @Test
    fun `a day lists its all-day events first, then the others by start time`() = runTest(dispatcher) {
        val day = month.atDay(10)
        seed(1, "Afternoon", millis(day, 14), millis(day, 15))
        seed(2, "Morning", millis(day, 9), millis(day, 10))
        seedAllDay(3, "Holiday", day)

        val titles = viewModel().state(testScheduler).cell(day).events.map { it.title }

        assertThat(titles).containsExactly("Holiday", "Morning", "Afternoon").inOrder()
    }

    @Test
    fun `an event over two days shows on both, and not on the day after`() = runTest(dispatcher) {
        val first = month.atDay(20)
        seed(1, "Trip", millis(first, 10), millis(first.plusDays(1), 18))

        val state = viewModel().state(testScheduler)

        assertThat(state.cell(first).events.map { it.title }).containsExactly("Trip")
        assertThat(state.cell(first.plusDays(1)).events.map { it.title }).containsExactly("Trip")
        assertThat(state.cell(first.plusDays(2)).events).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = [FarZones.AHEAD, FarZones.BEHIND])
    fun `an all-day event created in another time zone stays on its own date`(elsewhereId: String) = runTest(dispatcher) {
        // Its instants are the midnights of the zone it was created in. Read on this phone's clock they
        // fall inside two days, and the event showed on both — after any journey, or a backup restored
        // from a phone set to another zone.
        val day = month.atDay(14)
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

        val state = viewModel().state(testScheduler)

        assertThat(state.cell(day.minusDays(1)).events).isEmpty()
        assertThat(state.cell(day).events.map { it.title }).containsExactly("Holiday")
        assertThat(state.cell(day.plusDays(1)).events).isEmpty()
        // A tap hands the editor the instant the occurrence is known by, not its place on this calendar.
        assertThat(state.cell(day).events.single().occurrenceStartUtcMillis)
            .isEqualTo(eventRepo.rows.getValue(1).startUtcMillis)
    }

    @Test
    fun `an open month follows a change of time zone`() = runTest(dispatcher) {
        // Read once when the view model was made, the zone stayed the old one after a journey: a meeting
        // at 01:00 in Paris stayed on the 15th in New York, where it is 19:00 on the 14th.
        val paris = ZoneId.of("Europe/Paris")
        deviceZone.zone.value = paris
        val day = YearMonth.now(paris).atDay(15)
        val start = day.atTime(1, 0).atZone(paris).toInstant().toEpochMilli()
        eventRepo.rows[1] = Event(
            id = 1,
            calendarId = 1,
            title = "Early",
            startUtcMillis = start,
            endUtcMillis = start + 3_600_000,
            timeZoneId = paris.id,
        )
        val vm = viewModel()

        vm.uiState.test {
            testScheduler.advanceUntilIdle()
            assertThat(expectMostRecentItem().cell(day).events.map { it.title }).containsExactly("Early")

            deviceZone.zone.value = ZoneId.of("America/New_York")
            testScheduler.advanceUntilIdle()

            val there = expectMostRecentItem()
            assertThat(there.cell(day).events).isEmpty()
            assertThat(there.cell(day.minusDays(1)).events.map { it.title }).containsExactly("Early")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the first day of the week follows the language the screen is drawn in`() = runTest(dispatcher) {
        // First day set to "system", the app switched from American English to German while open: the
        // labels turned German and the grid kept starting on Sunday (measured on the Android 14 emulator).
        val vm = viewModel()

        vm.onLocaleChange(Locale.US)
        assertThat(vm.state(testScheduler).firstDayOfWeek).isEqualTo(DayOfWeek.SUNDAY)
        vm.onLocaleChange(Locale.GERMANY)
        assertThat(vm.state(testScheduler).firstDayOfWeek).isEqualTo(DayOfWeek.MONDAY)
    }

    @Test
    fun `the selected day's list is the same as its cell`() = runTest(dispatcher) {
        // The list under the dots, the sheet over the titles and the rows must never disagree on a day.
        val day = month.atDay(12)
        seed(1, "B", millis(day, 11), millis(day, 12))
        seed(2, "A", millis(day, 8), millis(day, 9))
        val vm = viewModel()

        vm.onSelectDate(day)
        val state = vm.state(testScheduler)

        assertThat(state.selectedDayOccurrences).isEqualTo(state.cell(day).events)
        assertThat(state.selectedDayOccurrences.map { it.title }).containsExactly("A", "B").inOrder()
    }

    @Test
    fun `the neighbouring months arrive filled in`() = runTest(dispatcher) {
        // So a swipe slides in a page that already shows its titles, instead of an empty grid that
        // fills itself once the swipe has settled.
        val next = month.plusMonths(1).atDay(15)
        val previous = month.minusMonths(1).atDay(15)
        seed(1, "Next", millis(next, 9), millis(next, 10))
        seed(2, "Previous", millis(previous, 9), millis(previous, 10))

        val state = viewModel().state(testScheduler)

        assertThat(state.pages.keys).containsExactly(month.minusMonths(1), month, month.plusMonths(1))
        assertThat(state.cell(next).events.map { it.title }).containsExactly("Next")
        assertThat(state.cell(previous).events.map { it.title }).containsExactly("Previous")
    }

    @Test
    fun `a month two away is not read`() = runTest(dispatcher) {
        // The window is the shown month and its neighbours, not the whole calendar.
        val far = month.plusMonths(3).atDay(15)
        seed(1, "Far", millis(far, 9), millis(far, 10))

        val state = viewModel().state(testScheduler)

        assertThat(state.pages.values.flatten().flatten().flatMap { it.events }).isEmpty()
    }

    @Test
    fun `the display comes from the settings, without waiting for the events`() = runTest(dispatcher) {
        // An event source that never answers: the display must still arrive. Bound to the database
        // instead, it stayed on its default for the seconds the database took to open.
        val settings = FakeSettingsRepository(AppSettings(monthDisplay = MonthDisplay.ROWS))
        val vm = MonthViewModel(
            observeOccurrences = ObserveOccurrencesInRangeUseCase(
                SilentEventRepository(eventRepo), calendarRepo, RecurrenceExpander(), dispatcher,
            ),
            calendarRepository = calendarRepo,
            eventRepository = eventRepo,
            settingsRepository = settings,
            deviceZone = deviceZone,
        )

        vm.display.test {
            testScheduler.advanceUntilIdle()
            assertThat(expectMostRecentItem()).isEqualTo(MonthDisplay.ROWS)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the grid uses the chosen first day of the week before the events arrive`() = runTest(dispatcher) {
        // The database still opening: the grid must already start on the user's day, not the locale's.
        val firstDay = if (java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).firstDayOfWeek ==
            java.time.DayOfWeek.MONDAY
        ) {
            WeekStart.SUNDAY
        } else {
            WeekStart.MONDAY
        }
        val settings = FakeSettingsRepository(AppSettings(weekStart = firstDay))
        val vm = MonthViewModel(
            observeOccurrences = ObserveOccurrencesInRangeUseCase(
                SilentEventRepository(eventRepo), calendarRepo, RecurrenceExpander(), dispatcher,
            ),
            calendarRepository = calendarRepo,
            eventRepository = eventRepo,
            settingsRepository = settings,
            deviceZone = deviceZone,
        )

        val state = vm.state(testScheduler)

        assertThat(state.firstDayOfWeek).isEqualTo(java.time.DayOfWeek.of(firstDay.rawValue))
        assertThat(state.weeks.first().first().date.dayOfWeek).isEqualTo(java.time.DayOfWeek.of(firstDay.rawValue))
    }

    @Test
    fun `coming back to the month keeps its events while they are read again`() = runTest(dispatcher) {
        // The flows restart when the screen returns after the stop timeout; starting them from an empty
        // list blanked the grid until the query answered — a blink on every return from the editor.
        val day = month.atDay(10)
        seed(1, "Morning", millis(day, 9), millis(day, 10))
        val vm = viewModel()
        assertThat(vm.state(testScheduler).cell(day).events).isNotEmpty()

        testScheduler.advanceTimeBy(MonthViewModel.STOP_TIMEOUT_MS + 1_000)

        vm.uiState.test {
            val states = mutableListOf(awaitItem())
            testScheduler.advanceUntilIdle()
            cancelAndConsumeRemainingEvents().forEach { event ->
                if (event is app.cash.turbine.Event.Item) states += event.value
            }
            states.forEach { assertThat(it.cell(day).events.map { e -> e.title }).containsExactly("Morning") }
        }
    }

    @Test
    fun `moving to another month selects its first day`() = runTest(dispatcher) {
        // Left on a day of the previous month, the selection kept a list under a month it no longer
        // belonged to — and two months away, outside the window read, that list was empty.
        val vm = viewModel()
        vm.onSelectDate(month.atDay(15))

        vm.onNextMonth()
        assertThat(vm.state(testScheduler).selectedDate).isEqualTo(month.plusMonths(1).atDay(1))

        vm.showMonth(month.plusMonths(3))
        assertThat(vm.state(testScheduler).selectedDate).isEqualTo(month.plusMonths(3).atDay(1))
    }

    @Test
    fun `coming back to the current month selects today`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onNextMonth()

        vm.onPreviousMonth()

        assertThat(vm.state(testScheduler).selectedDate).isEqualTo(LocalDate.now(zone))
    }

    @Test
    fun `a day tapped in the shown month keeps its selection when nothing moves`() = runTest(dispatcher) {
        // The pager reports the month it settles on; reporting the month already shown must not reset
        // a selection the user just made.
        val vm = viewModel()
        vm.onSelectDate(month.atDay(20))

        vm.showMonth(month)

        assertThat(vm.state(testScheduler).selectedDate).isEqualTo(month.atDay(20))
    }

    @Test
    fun `choosing a display remembers it`() = runTest(dispatcher) {
        val settings = FakeSettingsRepository()
        val vm = viewModel(settings)

        vm.setDisplay(MonthDisplay.TITLES)
        testScheduler.advanceUntilIdle()

        assertThat(settings.current().monthDisplay).isEqualTo(MonthDisplay.TITLES)
    }
}
