package com.filestech.agenda_tech.ui.screens.month

import app.cash.turbine.test
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander
import com.filestech.agenda_tech.domain.settings.AppSettings
import com.filestech.agenda_tech.domain.usecase.FakeCalendarRepository
import com.filestech.agenda_tech.domain.usecase.FakeEventRepository
import com.filestech.agenda_tech.domain.usecase.FakeSettingsRepository
import com.filestech.agenda_tech.domain.usecase.ObserveOccurrencesInRangeUseCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
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
 * The backup reminder.
 *
 * Unlike the restore offer, this one is allowed to come back — backing up is recurring by nature, so
 * asking once would only ever be declined once. That makes every rule about staying *quiet* the one
 * worth testing: an app that nags is an app that gets uninstalled, and this one interrupts to talk
 * about data loss.
 */
class BackupPromptTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val dispatcher = StandardTestDispatcher()

    /** Pinned "now": Wednesday 15 July 2026, 12:00. */
    private val now: Long = LocalDateTime.of(2026, 7, 15, 12, 0).atZone(zone).toInstant().toEpochMilli()
    private fun daysAgo(days: Long): Long = now - days * 24 * 60 * 60 * 1000L

    private val eventRepo = FakeEventRepository()
    private val calendarRepo = FakeCalendarRepository()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(settings: FakeSettingsRepository) = MonthViewModel(
        observeOccurrences = ObserveOccurrencesInRangeUseCase(eventRepo, calendarRepo, RecurrenceExpander(), dispatcher),
        calendarRepository = calendarRepo,
        eventRepository = eventRepo,
        settingsRepository = settings,
    ).apply { nowUtcMillis = { now } }

    private fun seedEvents(count: Int, lastChangeAt: Long = now) {
        repeat(count) { i ->
            val start = now + i * 3_600_000L
            eventRepo.rows[i + 1L] = Event(
                id = i + 1L,
                calendarId = 1,
                title = "Événement $i",
                startUtcMillis = start,
                endUtcMillis = start + 3_600_000,
                timeZoneId = zone.id,
            )
        }
        eventRepo.lastChangeAtUtcMillis = if (count == 0) 0L else lastChangeAt
    }

    // A WhileSubscribed StateFlow hands back its initial value when read without a collector — every
    // assertion goes through a real one, or "it stays quiet" would be true for the wrong reason.
    private suspend fun MonthViewModel.promptValue(scheduler: TestCoroutineScheduler): BackupPromptReason? {
        var value: BackupPromptReason? = null
        backupPrompt.test {
            scheduler.advanceUntilIdle()
            value = expectMostRecentItem()
            cancelAndIgnoreRemainingEvents()
        }
        return value
    }

    @Test
    fun `reminds when the agenda is full and was never backed up`() = runTest(dispatcher) {
        seedEvents(10)

        // NEVER, not STALE: telling someone their calendar "changed since their last backup" when
        // they never made one would be a lie.
        assertThat(viewModel(FakeSettingsRepository()).promptValue(testScheduler))
            .isEqualTo(BackupPromptReason.NEVER)
    }

    @Test
    fun `stays quiet while there is little to lose`() = runTest(dispatcher) {
        // Someone trying the app out is not someone to warn about losing their data.
        seedEvents(MonthViewModel.MIN_EVENTS_TO_SUGGEST_BACKUP - 1)

        assertThat(viewModel(FakeSettingsRepository()).promptValue(testScheduler)).isNull()
    }

    @Test
    fun `stays quiet on an empty agenda`() = runTest(dispatcher) {
        // That state belongs to the restore offer, not to this one.
        seedEvents(0)

        assertThat(viewModel(FakeSettingsRepository()).promptValue(testScheduler)).isNull()
    }

    @Test
    fun `stays quiet right after a backup`() = runTest(dispatcher) {
        seedEvents(20, lastChangeAt = daysAgo(1))
        val settings = FakeSettingsRepository(AppSettings(lastBackupAtUtcMillis = now))

        assertThat(viewModel(settings).promptValue(testScheduler)).isNull()
    }

    @Test
    fun `stays quiet on an old backup when nothing has changed since`() = runTest(dispatcher) {
        // The rule that keeps this honest: an agenda that has not moved has nothing new to lose,
        // however long ago it was exported. Reminding on time alone would be pure noise.
        seedEvents(20, lastChangeAt = daysAgo(200))
        val settings = FakeSettingsRepository(AppSettings(lastBackupAtUtcMillis = daysAgo(190)))

        assertThat(viewModel(settings).promptValue(testScheduler)).isNull()
    }

    @Test
    fun `reminds when the agenda changed after an old backup`() = runTest(dispatcher) {
        seedEvents(20, lastChangeAt = daysAgo(2))
        val settings = FakeSettingsRepository(
            AppSettings(lastBackupAtUtcMillis = daysAgo(MonthViewModel.STALE_BACKUP_DAYS + 5)),
        )

        assertThat(viewModel(settings).promptValue(testScheduler)).isEqualTo(BackupPromptReason.STALE)
    }

    @Test
    fun `stays quiet when the agenda changed but the backup is recent enough`() = runTest(dispatcher) {
        // Changes alone are not a reason to interrupt — everyone edits their calendar.
        seedEvents(20, lastChangeAt = daysAgo(1))
        val settings = FakeSettingsRepository(
            AppSettings(lastBackupAtUtcMillis = daysAgo(MonthViewModel.STALE_BACKUP_DAYS - 5)),
        )

        assertThat(viewModel(settings).promptValue(testScheduler)).isNull()
    }

    @Test
    fun `stays quiet while snoozed`() = runTest(dispatcher) {
        seedEvents(20)
        val settings = FakeSettingsRepository(
            AppSettings(backupPromptSnoozedUntilUtcMillis = now + 1000),
        )

        assertThat(viewModel(settings).promptValue(testScheduler)).isNull()
    }

    @Test
    fun `asks again once the snooze has run out`() = runTest(dispatcher) {
        // "Later" is not "never" — that distinction is the whole point of this reminder.
        seedEvents(20)
        val settings = FakeSettingsRepository(
            AppSettings(backupPromptSnoozedUntilUtcMillis = now - 1000),
        )

        assertThat(viewModel(settings).promptValue(testScheduler)).isEqualTo(BackupPromptReason.NEVER)
    }

    @Test
    fun `later persists a snooze of the expected length`() = runTest(dispatcher) {
        seedEvents(20)
        val settings = FakeSettingsRepository()
        val vm = viewModel(settings)

        vm.snoozeBackupPrompt()
        testScheduler.advanceUntilIdle()

        val expected = now + MonthViewModel.SNOOZE_DAYS * MonthViewModel.MILLIS_PER_DAY
        assertThat(settings.current().backupPromptSnoozedUntilUtcMillis).isEqualTo(expected)
    }
}
