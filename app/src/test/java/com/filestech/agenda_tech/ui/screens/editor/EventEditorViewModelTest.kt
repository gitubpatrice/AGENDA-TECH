package com.filestech.agenda_tech.ui.screens.editor

import androidx.lifecycle.SavedStateHandle
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.EventKind
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
import java.time.LocalDate
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

    // --- Audit D2 : le fuseau d'un événement existant ne doit pas être écrasé ---

    @Test
    fun `saving an imported event keeps the zone it was authored in`() = runTest(dispatcher) {
        // The whole point of the v6 repair migration is that a row imported from Outlook ends up with
        // a zone every reader can resolve. Opening that event to add a reminder used to overwrite it
        // with the device zone, which silently undid the repair — and, since the expander re-anchors
        // recurring occurrences to this field, moved every future occurrence by an hour across the
        // next DST transition.
        val tokyo = "Asia/Tokyo"
        eventRepo.rows[10] = seedEvent().copy(timeZoneId = tokyo)
        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()

        vm.onAddReminder(15)
        vm.onSave()
        testScheduler.advanceUntilIdle()

        assertThat(eventRepo.rows.getValue(10).timeZoneId).isEqualTo(tokyo)
    }

    @Test
    fun `retyping the time re-anchors the event to the device zone`() = runTest(dispatcher) {
        // ⚠️ This test asserted the OPPOSITE until audit DR-6, and the opposite was a defect I had
        // pinned with a test — the worst way to be wrong, because it makes the defect load-bearing.
        //
        // The reasoning that produced it stopped one step early. Preserving the authored zone is right
        // when the user opens an event to add a reminder (that is D2). It is wrong the moment they
        // retype a time, because this editor reads and writes wall-clock times in the DEVICE zone and
        // offers no zone picker: typing 11:00 in Paris on a meeting authored in Asia/Tokyo produces
        // the instant 18:00 JST. Keeping the Tokyo label then makes RecurrenceExpander anchor every
        // later occurrence to 18:00 JST — Japan has no summer time, France does, so after October the
        // user sees 10:00 for a series they set to 11:00. F3's symptom, roles reversed.
        //
        // The only honest reading of a time typed here is "local", so a moved event is re-anchored.
        eventRepo.rows[10] = seedEvent().copy(timeZoneId = "Asia/Tokyo")
        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()

        vm.onStartTimeChange(11, 0)
        vm.onSave()
        testScheduler.advanceUntilIdle()

        val saved = eventRepo.rows.getValue(10)
        assertThat(saved.startUtcMillis).isEqualTo(at(2026, 7, 20, 11))
        assertThat(saved.timeZoneId).isEqualTo(zone.id)
    }

    @Test
    fun `a zone the app cannot resolve is not preserved`() = runTest(dispatcher) {
        // Audit DR-5. Before D2 the editor overwrote this field with the device zone and therefore
        // repaired any unresolvable row by accident; preserving the loaded value removed that safety
        // net along with the defect. `EntityMappers` deliberately does not re-normalise on read, so
        // this is the last place the invariant can be kept.
        eventRepo.rows[10] = seedEvent().copy(timeZoneId = "Romance Standard Time")
        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()

        vm.onAddReminder(15)
        vm.onSave()
        testScheduler.advanceUntilIdle()

        assertThat(eventRepo.rows.getValue(10).timeZoneId).isEqualTo(zone.id)
    }

    @Test
    fun `a new event is authored in the device zone`() = runTest(dispatcher) {
        // The other half: with nothing to preserve, the device zone is the right answer. A test that
        // only pinned the first half would be satisfied by never writing the field at all.
        val vm = viewModel()
        vm.onTitleChange("Nouveau")
        vm.onSave()
        testScheduler.advanceUntilIdle()

        assertThat(eventRepo.rows.values.single { it.title == "Nouveau" }.timeZoneId).isEqualTo(zone.id)
    }

    @Test
    fun `an all-day event keeps the device zone its boundaries were computed in`() = runTest(dispatcher) {
        // For an all-day row the zone is not a label but part of the arithmetic: the instants ARE
        // midnight-to-midnight in this zone. Preserving a foreign zone beside device-zone boundaries
        // would put the two out of step — the inconsistency DeviceEventMapper and IcsCodec avoid by
        // anchoring all-day rows to the device zone on both sides.
        eventRepo.rows[10] = seedEvent().copy(timeZoneId = "Asia/Tokyo", allDay = true)
        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()

        vm.onAllDayChange(true)
        vm.onSave()
        testScheduler.advanceUntilIdle()

        assertThat(eventRepo.rows.getValue(10).timeZoneId).isEqualTo(zone.id)
    }

    // --- EXDATE : une annulation ne doit pas survivre à un renommage de série ------

    @Test
    fun `renaming a series keeps the occurrences that were cancelled from it`() = runTest(dispatcher) {
        // FIAB-DUP-1. The editor has no field for EXDATEs, so it used to rebuild the rule without
        // them: one rename brought every deleted occurrence back. For a *moved* occurrence it was
        // worse — the override row survives, so the event showed up twice on the same day.
        val cancelled = at(2026, 7, 27, 9)
        seedEvent(
            id = 10,
            title = "Sport",
            recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY, exDatesUtcMillis = listOf(cancelled)),
        )

        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()
        vm.onTitleChange("Sport — renommé")
        vm.onSave()
        testScheduler.advanceUntilIdle()

        assertThat(eventRepo.rows.getValue(10).recurrence!!.exDatesUtcMillis).containsExactly(cancelled)
    }

    // --- Duplication --------------------------------------------------------

    @Test
    fun `duplicating inserts a second row and leaves the original untouched`() = runTest(dispatcher) {
        seedEvent(id = 10, title = "Dentiste")
        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()
        vm.onAddReminder(15)

        vm.onDuplicate("Dentiste (copie)")
        vm.onSave()
        testScheduler.advanceUntilIdle()

        val original = eventRepo.rows.getValue(10)
        val copy = eventRepo.rows.values.single { it.id != 10L }
        assertThat(original.title).isEqualTo("Dentiste")
        assertThat(copy.title).isEqualTo("Dentiste (copie)")
        // Same content, different identity — the whole contract of "duplicate".
        assertThat(copy.startUtcMillis).isEqualTo(original.startUtcMillis)
        assertThat(copy.calendarId).isEqualTo(original.calendarId)
        // The reminders follow the copy, and only the copy.
        assertThat(reminderRepo.rows.values.map { it.eventId }).containsExactly(copy.id)
    }

    @Test
    fun `a duplicate claims neither the source uid nor the override link of its original`() =
        runTest(dispatcher) {
            // Two rows sharing one uid would fight over the same slot in the device import's
            // uid → id map, and a copy presenting itself as an override would replace an
            // occurrence of a series it is not part of.
            eventRepo.rows[10] = seedEvent(id = 10, sourceUid = "uid-from-exchange")
                .copy(recurrenceParentId = 7L, originalStartUtcMillis = at(2026, 7, 20, 9))

            val vm = viewModel(eventId = 10)
            testScheduler.advanceUntilIdle()
            vm.onDuplicate("Dentiste (copie)")
            vm.onSave()
            testScheduler.advanceUntilIdle()

            val copy = eventRepo.rows.values.single { it.id != 10L }
            assertThat(copy.sourceUid).isNull()
            assertThat(copy.recurrenceParentId).isNull()
            assertThat(copy.originalStartUtcMillis).isNull()
        }

    @Test
    fun `duplicating a series copies the rule but none of its cancelled occurrences`() =
        runTest(dispatcher) {
            val cancelled = at(2026, 7, 27, 9)
            seedEvent(
                id = 10,
                title = "Sport",
                recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY, exDatesUtcMillis = listOf(cancelled)),
            )

            val vm = viewModel(eventId = 10, occurrenceStart = at(2026, 8, 3, 9))
            testScheduler.advanceUntilIdle()
            vm.onDuplicate("Sport (copie)")
            vm.onSave()
            testScheduler.advanceUntilIdle()

            // No scope dialog: the copy belongs to no series, so there is nothing to ask about.
            assertThat(vm.state.value.scopePrompt).isNull()
            val copy = eventRepo.rows.values.single { it.id != 10L }
            assertThat(copy.recurrence!!.freq).isEqualTo(RecurrenceFreq.WEEKLY)
            assertThat(copy.recurrence!!.exDatesUtcMillis).isEmpty()
            assertThat(eventRepo.rows.getValue(10).recurrence!!.exDatesUtcMillis).containsExactly(cancelled)
        }

    @Test
    fun `duplicating an unsaved event does nothing`() = runTest(dispatcher) {
        val vm = viewModel()
        testScheduler.advanceUntilIdle()
        vm.onTitleChange("Brouillon")

        vm.onDuplicate("Brouillon (copie)")

        assertThat(vm.state.value.title).isEqualTo("Brouillon")
    }

    // --- La fenêtre avant chargement (relecture gpt-5.2, 2026-08-31) ---------

    @Test
    fun `duplicating before the row has been read is ignored`() = runTest(dispatcher) {
        // `isEditing` comes from the nav argument, so it is true one frame after the editor opens —
        // long before the encrypted read lands. Without the isLoaded guard, onDuplicate cleared the
        // loaded* fields, then loadEvent repopulated them, and the "copy" saved with the ORIGINAL's
        // source uid and override link. Not advancing the scheduler reproduces exactly that window.
        eventRepo.rows[10] = seedEvent(id = 10, sourceUid = "uid-from-exchange")

        val vm = viewModel(eventId = 10)
        vm.onDuplicate("Dentiste (copie)") // le chargement n'a pas encore eu lieu
        testScheduler.advanceUntilIdle()
        vm.onSave()
        testScheduler.advanceUntilIdle()

        // Refusée, donc l'éditeur édite toujours la même ligne : une seule, et son uid intact.
        assertThat(eventRepo.rows.keys).containsExactly(10L)
        assertThat(eventRepo.rows.getValue(10).sourceUid).isEqualTo("uid-from-exchange")
    }

    @Test
    fun `deleting before the row has been read is ignored`() = runTest(dispatcher) {
        // The damaging half of the same window: `loadedRecurrence` is still null, so
        // isMasterOccurrence() answers "plain event" about a series and the delete would take the
        // master outright — the user tapped one occurrence and loses every one of them.
        seedEvent(id = 10, title = "Sport", recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY))

        val vm = viewModel(eventId = 10, occurrenceStart = at(2026, 7, 27, 9))
        vm.onDelete()
        testScheduler.advanceUntilIdle()

        assertThat(eventRepo.rows.keys).containsExactly(10L)
        assertThat(vm.state.value.isDeleted).isFalse()
    }

    @Test
    fun `duplicating drops a scope question that was already on screen`() = runTest(dispatcher) {
        // Answering it afterwards would run persist(asOverride = true) with eventId already NEW,
        // writing an override whose master id is -1.
        seedEvent(id = 10, title = "Sport", recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY))

        val vm = viewModel(eventId = 10, occurrenceStart = at(2026, 7, 27, 9))
        testScheduler.advanceUntilIdle()
        vm.onSave()
        assertThat(vm.state.value.scopePrompt).isEqualTo(ScopePrompt.SAVE)

        vm.onDuplicate("Sport (copie)")
        vm.confirmScope(applyToSeries = false) // réponse tardive à une question caduque
        testScheduler.advanceUntilIdle()

        assertThat(vm.state.value.scopePrompt).isNull()
        assertThat(eventRepo.rows.keys).containsExactly(10L)
        assertThat(eventRepo.rows.values.none { it.recurrenceParentId != null }).isTrue()
    }

    // --- Anniversaires ------------------------------------------------------

    @Test
    fun `choosing birthday fills in what a birthday always is`() = runTest(dispatcher) {
        val vm = viewModel()
        testScheduler.advanceUntilIdle()

        vm.onKindChange(EventKind.BIRTHDAY)

        val state = vm.state.value
        assertThat(state.allDay).isTrue()
        assertThat(state.recurrenceFreq).isEqualTo(RecurrenceFreq.YEARLY)
        assertThat(state.recurrenceInterval).isEqualTo(1)
        assertThat(state.recurrenceEnd).isEqualTo(RecurrenceEnd.NEVER)
        // Its own day, not the hour the editor proposes for an ordinary event.
        assertThat(state.endDateTime).isEqualTo(state.startDateTime)
    }

    @Test
    fun `a saved birthday is a yearly all-day event that knows it is one`() = runTest(dispatcher) {
        val vm = viewModel()
        testScheduler.advanceUntilIdle()
        vm.onKindChange(EventKind.BIRTHDAY)
        vm.onTitleChange("Paul")
        vm.onStartDateChange(LocalDate.of(1984, 3, 12))
        vm.onSave()
        testScheduler.advanceUntilIdle()

        val saved = eventRepo.rows.values.single()
        assertThat(saved.kind).isEqualTo(EventKind.BIRTHDAY)
        assertThat(saved.allDay).isTrue()
        assertThat(saved.recurrence!!.freq).isEqualTo(RecurrenceFreq.YEARLY)
        assertThat(saved.recurrence!!.isInfinite).isTrue()
        // Exactly one day: an all-day end is stored as the exclusive next midnight.
        assertThat(saved.endUtcMillis - saved.startUtcMillis).isEqualTo(ONE_DAY_MILLIS)
    }

    @Test
    fun `moving a birth date to a later year does not stretch the birthday`() = runTest(dispatcher) {
        // Without the special case in withStart, the generic rule keeps the old end whenever it is not
        // before the new start — so pushing the birth date forward would leave an all-day event
        // spanning every day in between, silently filling years of the agenda.
        val vm = viewModel()
        testScheduler.advanceUntilIdle()
        vm.onKindChange(EventKind.BIRTHDAY)
        vm.onTitleChange("Paul")
        vm.onStartDateChange(LocalDate.of(1984, 3, 12))
        vm.onStartDateChange(LocalDate.of(1990, 3, 12))
        vm.onSave()
        testScheduler.advanceUntilIdle()

        val saved = eventRepo.rows.values.single()
        assertThat(saved.endUtcMillis - saved.startUtcMillis).isEqualTo(ONE_DAY_MILLIS)
    }

    @Test
    fun `editing a birthday keeps it one`() = runTest(dispatcher) {
        eventRepo.rows[10] = seedEvent(id = 10, title = "Paul").copy(
            kind = EventKind.BIRTHDAY,
            allDay = true,
            recurrence = RecurrenceRule(freq = RecurrenceFreq.YEARLY),
        )
        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.kind).isEqualTo(EventKind.BIRTHDAY)

        vm.onTitleChange("Paul Durand")
        vm.onSave()
        testScheduler.advanceUntilIdle()

        assertThat(eventRepo.rows.getValue(10).kind).isEqualTo(EventKind.BIRTHDAY)
    }

    private companion object {
        const val ONE_DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
