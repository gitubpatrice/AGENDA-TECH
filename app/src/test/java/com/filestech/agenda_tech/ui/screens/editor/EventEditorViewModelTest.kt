package com.filestech.agenda_tech.ui.screens.editor

import androidx.lifecycle.SavedStateHandle
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.model.Reminder
import com.filestech.agenda_tech.domain.usecase.DeleteEventUseCase
import com.filestech.agenda_tech.domain.usecase.FakeCalendarRepository
import com.filestech.agenda_tech.domain.usecase.FakeEventRepository
import com.filestech.agenda_tech.domain.usecase.FakeReminderRepository
import com.filestech.agenda_tech.domain.usecase.FakeSettingsRepository
import com.filestech.agenda_tech.domain.usecase.UpsertEventUseCase
import com.filestech.agenda_tech.system.alarm.ReminderScheduler
import com.filestech.agenda_tech.ui.navigation.Routes
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The editor's save/delete paths — the most-walked code in the app, and the one that had no net.
 *
 * These exist because of what happened on 15 July 2026: saving an edited event that carried a
 * reminder crashed, in every release since the first, and neither two full audits nor 183 tests saw
 * it. A user did. The bug itself lived one layer down (a repository returning Room's `-1`), so these
 * tests would not have caught *that* one — a fake honours the contract the implementation broke.
 * What they do catch is everything this file decides: which id the reminders are attached to, that
 * alarms are cancelled before their rows are replaced, and that an override never silently becomes a
 * whole-series edit.
 */
class EventEditorViewModelTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private fun at(y: Int, m: Int, d: Int, h: Int): Long =
        LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    private val eventRepo = FakeEventRepository()
    private val calendarRepo = FakeCalendarRepository()
    private val reminderRepo = FakeReminderRepository()
    private val settingsRepo = FakeSettingsRepository()

    /**
     * Mocked, not faked: [ReminderScheduler] is a final class wired to AlarmManager, so there is no
     * seam to implement. What matters here is *which calls it receives, and in what order* — exactly
     * what a mock verifies.
     */
    private val scheduler: ReminderScheduler = mockk(relaxed = true)

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        // viewModelScope pins Dispatchers.Main, which does not exist off-device.
        Dispatchers.setMain(dispatcher)
        calendarRepo.stored += Calendar(id = 1, name = "Perso", isDefault = true)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        eventId: Long? = null,
        occurrenceStart: Long? = null,
    ): EventEditorViewModel {
        val args = buildMap<String, Any> {
            eventId?.let { put(Routes.ARG_EVENT_ID, it) }
            occurrenceStart?.let { put(Routes.ARG_OCCURRENCE_START, it) }
        }
        return EventEditorViewModel(
            upsertEvent = UpsertEventUseCase(eventRepo),
            deleteEvent = DeleteEventUseCase(eventRepo),
            eventRepository = eventRepo,
            calendarRepository = calendarRepo,
            reminderRepository = reminderRepo,
            reminderScheduler = scheduler,
            settingsRepository = settingsRepo,
            savedStateHandle = SavedStateHandle(args),
        )
    }

    private fun seedEvent(
        id: Long = 10,
        title: String = "Dentiste",
        recurrence: RecurrenceRule? = null,
        sourceUid: String? = null,
    ) = Event(
        id = id,
        calendarId = 1,
        title = title,
        startUtcMillis = at(2026, 7, 20, 9),
        endUtcMillis = at(2026, 7, 20, 10),
        timeZoneId = zone.id,
        recurrence = recurrence,
        sourceUid = sourceUid,
    ).also { eventRepo.rows[id] = it }

    // --- Enregistrement d'un nouvel événement -------------------------------

    @Test
    fun `saving a new event attaches its reminders to the id the insert produced`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onTitleChange("Dentiste")
        vm.onAddReminder(10)
        vm.onSave()
        testScheduler.advanceUntilIdle()

        val saved = eventRepo.rows.values.single()
        // The whole point: reminders must hang off the real row, never off a placeholder.
        assertThat(reminderRepo.rows.values.map { it.eventId }).containsExactly(saved.id)
        assertThat(saved.id).isGreaterThan(0L)
        assertThat(vm.state.value.isSaved).isTrue()
    }

    @Test
    fun `a blank title is refused before anything is written`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onTitleChange("   ")
        vm.onSave()
        testScheduler.advanceUntilIdle()

        assertThat(eventRepo.rows).isEmpty()
        assertThat(vm.state.value.error).isEqualTo(EditorError.BLANK_TITLE)
        assertThat(vm.state.value.isSaved).isFalse()
    }

    @Test
    fun `an end before its start is refused`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onTitleChange("Dentiste")
        vm.onStartTimeChange(14, 0)
        vm.onEndTimeChange(9, 0)
        vm.onSave()
        testScheduler.advanceUntilIdle()

        assertThat(eventRepo.rows).isEmpty()
        assertThat(vm.state.value.error).isEqualTo(EditorError.END_BEFORE_START)
    }

    // --- Édition d'un événement existant ------------------------------------

    @Test
    fun `editing an existing event keeps its id and re-attaches the reminders to it`() = runTest(dispatcher) {
        seedEvent(id = 10)
        reminderRepo.rows[500] = Reminder(id = 500, eventId = 10, minutesBefore = 10)

        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()
        vm.onTitleChange("Dentiste — reporté")
        vm.onSave()
        testScheduler.advanceUntilIdle()

        // The edit updates row 10 in place; it must not spawn a second event.
        assertThat(eventRepo.rows.keys).containsExactly(10L)
        assertThat(eventRepo.rows[10]!!.title).isEqualTo("Dentiste — reporté")
        // Reminders are rewritten, and every one of them points at the event that exists.
        assertThat(reminderRepo.rows.values.map { it.eventId }).containsExactly(10L)
    }

    @Test
    fun `alarms are cancelled before the reminder rows are replaced`() = runTest(dispatcher) {
        // Audit SEC-1: rewriting the rows first would leave the old PendingIntents armed against ids
        // that no longer exist — phantom notifications for an event the user just changed.
        //
        // Verified as a *sequence* spanning the scheduler and the repository: checking each side on
        // its own would still pass with the two calls inverted, which is the whole failure mode.
        seedEvent(id = 10)
        reminderRepo.rows[500] = Reminder(id = 500, eventId = 10, minutesBefore = 10)
        coEvery { scheduler.cancelEvent(any()) } answers { reminderRepo.callLog += "cancelAlarms(${firstArg<Long>()})" }

        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()
        vm.onTitleChange("Dentiste")
        vm.onSave()
        testScheduler.advanceUntilIdle()

        assertThat(reminderRepo.callLog).containsExactly("cancelAlarms(10)", "deleteRows(10)").inOrder()
    }

    @Test
    fun `editing an imported event keeps its source uid`() = runTest(dispatcher) {
        // Dropping it detaches the row from its source, and the next import re-inserts it as a
        // duplicate instead of updating it — the exact bug that produced doubled events before.
        seedEvent(id = 10, sourceUid = "uid-abc")

        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()
        vm.onTitleChange("Renommé à la main")
        vm.onSave()
        testScheduler.advanceUntilIdle()

        assertThat(eventRepo.rows[10]!!.sourceUid).isEqualTo("uid-abc")
    }

    @Test
    fun `removing every reminder leaves none behind and disarms the alarm`() = runTest(dispatcher) {
        seedEvent(id = 10)
        reminderRepo.rows[500] = Reminder(id = 500, eventId = 10, minutesBefore = 10)

        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()
        vm.onRemoveReminder(10)
        vm.onSave()
        testScheduler.advanceUntilIdle()

        assertThat(reminderRepo.rows).isEmpty()
        coVerify { scheduler.cancelEvent(10) }
    }

    // --- Occurrence d'une série : le choix de portée ------------------------

    @Test
    fun `tapping one occurrence of a series asks before writing anything`() = runTest(dispatcher) {
        seedEvent(id = 10, title = "Sport", recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY))

        val vm = viewModel(eventId = 10, occurrenceStart = at(2026, 7, 27, 9))
        testScheduler.advanceUntilIdle()
        vm.onTitleChange("Sport — modifié")
        vm.onSave()
        testScheduler.advanceUntilIdle()

        // Nothing is persisted until the user says "this one" or "the series": the stored row still
        // carries its original title, not the one just typed.
        assertThat(vm.state.value.scopePrompt).isEqualTo(ScopePrompt.SAVE)
        assertThat(vm.state.value.isSaved).isFalse()
        assertThat(eventRepo.rows[10]!!.title).isEqualTo("Sport")
    }

    @Test
    fun `choosing this occurrence writes an override and excludes its date from the master`() = runTest(dispatcher) {
        val occurrence = at(2026, 7, 27, 9)
        // Seeded WITH a source uid: without one, asserting the override does not claim it would be
        // vacuously true — the master has nothing to claim.
        seedEvent(
            id = 10,
            title = "Sport",
            recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY),
            sourceUid = "uid-master",
        )

        val vm = viewModel(eventId = 10, occurrenceStart = occurrence)
        testScheduler.advanceUntilIdle()
        vm.onTitleChange("Sport — déplacé")
        vm.onSave()
        testScheduler.advanceUntilIdle()
        vm.confirmScope(applyToSeries = false)
        testScheduler.advanceUntilIdle()

        // The master keeps its title and its rule, but no longer produces that instant…
        val master = eventRepo.rows[10]!!
        assertThat(master.title).isEqualTo("Sport")
        assertThat(master.recurrence!!.exDatesUtcMillis).contains(occurrence)
        // …and a separate, non-recurring row now owns it.
        val override = eventRepo.rows.values.single { it.recurrenceParentId == 10L }
        assertThat(override.title).isEqualTo("Sport — déplacé")
        assertThat(override.originalStartUtcMillis).isEqualTo(occurrence)
        assertThat(override.recurrence).isNull()
        // An override must not claim the master's uid — two rows would fight for one import slot.
        assertThat(override.sourceUid).isNull()
        // …and the master must keep it, or the next import would re-insert the series as a duplicate.
        assertThat(master.sourceUid).isEqualTo("uid-master")
        // Both the override's alarm and the master's (which lost a date) are re-armed.
        coVerify { scheduler.rescheduleEvent(override.id) }
        coVerify { scheduler.rescheduleEvent(10) }
    }

    @Test
    fun `choosing the whole series edits the master in place and adds no override`() = runTest(dispatcher) {
        seedEvent(id = 10, title = "Sport", recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY))

        val vm = viewModel(eventId = 10, occurrenceStart = at(2026, 7, 27, 9))
        testScheduler.advanceUntilIdle()
        vm.onTitleChange("Sport — renommé")
        vm.onSave()
        testScheduler.advanceUntilIdle()
        vm.confirmScope(applyToSeries = true)
        testScheduler.advanceUntilIdle()

        assertThat(eventRepo.rows.keys).containsExactly(10L)
        assertThat(eventRepo.rows[10]!!.title).isEqualTo("Sport — renommé")
        assertThat(eventRepo.rows[10]!!.recurrence).isNotNull()
        assertThat(eventRepo.rows.values.none { it.recurrenceParentId != null }).isTrue()
    }

    @Test
    fun `dismissing the scope prompt writes nothing`() = runTest(dispatcher) {
        seedEvent(id = 10, title = "Sport", recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY))

        val vm = viewModel(eventId = 10, occurrenceStart = at(2026, 7, 27, 9))
        testScheduler.advanceUntilIdle()
        vm.onTitleChange("Sport — annulé")
        vm.onSave()
        testScheduler.advanceUntilIdle()
        vm.dismissScope()
        testScheduler.advanceUntilIdle()

        assertThat(eventRepo.rows[10]!!.title).isEqualTo("Sport")
        assertThat(vm.state.value.scopePrompt).isNull()
        assertThat(vm.state.value.isSaved).isFalse()
    }

    // --- Suppression --------------------------------------------------------

    @Test
    fun `deleting a plain event removes it and disarms its alarms`() = runTest(dispatcher) {
        seedEvent(id = 10)

        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()
        vm.onDelete()
        testScheduler.advanceUntilIdle()

        assertThat(eventRepo.rows).isEmpty()
        assertThat(vm.state.value.isDeleted).isTrue()
        coVerify { scheduler.cancelEvent(10) }
    }

    @Test
    fun `deleting the whole series removes the master and its overrides together`() = runTest(dispatcher) {
        seedEvent(id = 10, title = "Sport", recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY))
        eventRepo.rows[11] = Event(
            id = 11,
            calendarId = 1,
            title = "Sport — déplacé",
            startUtcMillis = at(2026, 7, 28, 9),
            endUtcMillis = at(2026, 7, 28, 10),
            timeZoneId = zone.id,
            recurrenceParentId = 10,
            originalStartUtcMillis = at(2026, 7, 27, 9),
        )

        val vm = viewModel(eventId = 10, occurrenceStart = at(2026, 7, 27, 9))
        testScheduler.advanceUntilIdle()
        vm.onDelete()
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.scopePrompt).isEqualTo(ScopePrompt.DELETE)

        vm.confirmScope(applyToSeries = true)
        testScheduler.advanceUntilIdle()

        // An orphaned override would keep showing on the calendar with no series behind it.
        assertThat(eventRepo.rows).isEmpty()
        assertThat(vm.state.value.isDeleted).isTrue()
    }

    @Test
    fun `deleting a modified occurrence excludes its date instead of reviving the master's`() = runTest(dispatcher) {
        // FIAB-4: dropping the override alone would let the master's default occurrence reappear that
        // day — "delete" would silently behave as "revert to the original".
        val occurrence = at(2026, 7, 27, 9)
        seedEvent(id = 10, title = "Sport", recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY))
        eventRepo.rows[11] = Event(
            id = 11,
            calendarId = 1,
            title = "Sport — déplacé",
            startUtcMillis = at(2026, 7, 28, 9),
            endUtcMillis = at(2026, 7, 28, 10),
            timeZoneId = zone.id,
            recurrenceParentId = 10,
            originalStartUtcMillis = occurrence,
        )

        val vm = viewModel(eventId = 11)
        testScheduler.advanceUntilIdle()
        vm.onDelete()
        testScheduler.advanceUntilIdle()

        assertThat(eventRepo.rows.keys).containsExactly(10L)
        assertThat(eventRepo.rows[10]!!.recurrence!!.exDatesUtcMillis).contains(occurrence)
        assertThat(vm.state.value.isDeleted).isTrue()
        coVerify { scheduler.rescheduleEvent(10) }
    }

    @Test
    fun `deleting an unsaved event does nothing`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onDelete()
        testScheduler.advanceUntilIdle()

        assertThat(vm.state.value.isDeleted).isFalse()
        coVerify(exactly = 0) { scheduler.cancelEvent(any()) }
    }

    // --- Valeurs par défaut -------------------------------------------------

    @Test
    fun `a new event seeds its colour and reminder from the user's settings`() = runTest(dispatcher) {
        val settings = FakeSettingsRepository(
            com.filestech.agenda_tech.domain.settings.AppSettings(
                defaultEventColor = CalendarColor.TOMATO,
                defaultReminderMinutes = 30,
            ),
        )
        val vm = EventEditorViewModel(
            upsertEvent = UpsertEventUseCase(eventRepo),
            deleteEvent = DeleteEventUseCase(eventRepo),
            eventRepository = eventRepo,
            calendarRepository = calendarRepo,
            reminderRepository = reminderRepo,
            reminderScheduler = scheduler,
            settingsRepository = settings,
            savedStateHandle = SavedStateHandle(emptyMap()),
        )
        testScheduler.advanceUntilIdle()

        assertThat(vm.state.value.colorOverride).isEqualTo(CalendarColor.TOMATO)
        assertThat(vm.state.value.reminderMinutes).containsExactly(30)
    }
}
