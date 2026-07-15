package com.filestech.agenda_tech.ui.screens.month

import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander
import com.filestech.agenda_tech.domain.settings.AppSettings
import com.filestech.agenda_tech.domain.usecase.FakeCalendarRepository
import com.filestech.agenda_tech.domain.usecase.FakeEventRepository
import com.filestech.agenda_tech.domain.usecase.FakeSettingsRepository
import com.filestech.agenda_tech.domain.usecase.ObserveOccurrencesInRangeUseCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import app.cash.turbine.test
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The offer to restore a backup, shown on an empty agenda.
 *
 * The rule that matters is the one about *not* showing it: a calendar can be empty because its owner
 * wants it empty, and an offer that returns on every launch stops being help.
 */
class RestorePromptTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val dispatcher = StandardTestDispatcher()

    private val eventRepo = FakeEventRepository()
    private val calendarRepo = FakeCalendarRepository()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(settings: FakeSettingsRepository) = MonthViewModel(
        observeOccurrences = ObserveOccurrencesInRangeUseCase(
            eventRepo,
            calendarRepo,
            RecurrenceExpander(),
            dispatcher,
        ),
        calendarRepository = calendarRepo,
        eventRepository = eventRepo,
        settingsRepository = settings,
    )

    private fun seedEvent() {
        val start = LocalDateTime.of(2026, 7, 20, 9, 0).atZone(zone).toInstant().toEpochMilli()
        eventRepo.rows[10] = Event(
            id = 10,
            calendarId = 1,
            title = "Dentiste",
            startUtcMillis = start,
            endUtcMillis = start + 3_600_000,
            timeZoneId = zone.id,
        )
    }

    // `showRestorePrompt` is a WhileSubscribed StateFlow: read `.value` without collecting and it
    // hands back its initial `false` without ever running. Every assertion below therefore goes
    // through a real collector — otherwise "is it hidden?" would be true for the wrong reason.
    private suspend fun MonthViewModel.promptValue(scheduler: TestCoroutineScheduler): Boolean {
        var value = false
        showRestorePrompt.test {
            scheduler.advanceUntilIdle()
            value = expectMostRecentItem()
            cancelAndIgnoreRemainingEvents()
        }
        return value
    }

    @Test
    fun `offered on an empty agenda that has never answered`() = runTest(dispatcher) {
        val vm = viewModel(FakeSettingsRepository())

        assertThat(vm.promptValue(testScheduler)).isTrue()
    }

    @Test
    fun `never offered once the agenda holds an event`() = runTest(dispatcher) {
        // The one moment a backup is worth offering is a calendar with nothing in it. Past that, the
        // offer is noise on top of someone's real data.
        seedEvent()
        val vm = viewModel(FakeSettingsRepository())

        assertThat(vm.promptValue(testScheduler)).isFalse()
    }

    @Test
    fun `never offered again once answered, even though the agenda is still empty`() = runTest(dispatcher) {
        // Declining is an answer. A deliberately empty calendar must not be asked on every launch.
        val vm = viewModel(FakeSettingsRepository(AppSettings(restorePromptDismissed = true)))

        assertThat(vm.promptValue(testScheduler)).isFalse()
    }

    @Test
    fun `dismissing it persists the answer`() = runTest(dispatcher) {
        val settings = FakeSettingsRepository()
        val vm = viewModel(settings)
        testScheduler.advanceUntilIdle()

        vm.dismissRestorePrompt()
        testScheduler.advanceUntilIdle()

        // Persisted, not just hidden: the next launch must not ask again.
        assertThat(settings.current().restorePromptDismissed).isTrue()
    }
}
