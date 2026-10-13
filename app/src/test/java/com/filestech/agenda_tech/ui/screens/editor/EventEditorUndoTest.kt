package com.filestech.agenda_tech.ui.screens.editor

import com.filestech.agenda_tech.domain.model.DeletedEvents
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.model.Reminder
import com.filestech.agenda_tech.domain.model.SeriesExclusion
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * What the editor offers to undo after each of its four ways of deleting. The restore itself is
 * DeletionRepositoryImpl's (instrumented) and DeletionUndo's tests; here, the question is whether the
 * offer holds exactly what the deletion took — no less (an undo that loses the reminders), no more (an
 * undo that lifts an exclusion it did not add, and doubles an occurrence).
 */
internal class EventEditorUndoTest : EventEditorTestBase() {

    private val weekly = RecurrenceRule(freq = RecurrenceFreq.WEEKLY)

    private fun reminder(id: Long, eventId: Long, minutes: Int) {
        reminderRepo.rows[id] = Reminder(id = id, eventId = eventId, minutesBefore = minutes)
    }

    private fun offered(): DeletedEvents {
        val captured = slot<DeletedEvents>()
        verify(exactly = 1) { deletionUndo.offer(capture(captured)) }
        return captured.captured
    }

    private fun seedOverride(id: Long, masterId: Long, originalStart: Long) = Event(
        id = id,
        calendarId = 1,
        title = "Sport — déplacé",
        startUtcMillis = originalStart + HOUR,
        endUtcMillis = originalStart + 2 * HOUR,
        timeZoneId = zone.id,
        recurrenceParentId = masterId,
        originalStartUtcMillis = originalStart,
    ).also { eventRepo.rows[id] = it }

    @Test
    fun `deleting an event deletes what was shared of it`() = runTest(dispatcher) {
        // Security audit, 2026-10-13: the shared .ics survived, unencrypted, the event it was a copy of.
        seedEvent(id = 10)

        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()
        vm.onDelete()
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { sharedFiles.forget(10) }
    }

    @Test
    fun `deleting a moved occurrence deletes what was shared of its series`() = runTest(dispatcher) {
        // A share of an occurrence is filed under its series: that is what the .ics carries.
        val occurrence = at(2026, 7, 27, 9)
        seedEvent(id = 10, title = "Sport", recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY))
        seedOverride(id = 11, masterId = 10, originalStart = occurrence)

        val vm = viewModel(eventId = 11)
        testScheduler.advanceUntilIdle()
        vm.onDelete()
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { sharedFiles.forget(10) }
    }

    @Test
    fun `deleting an event offers it back with the reminders it carried`() = runTest(dispatcher) {
        val event = seedEvent(id = 10)
        reminder(1, eventId = 10, minutes = 15)
        reminder(2, eventId = 10, minutes = 60)

        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()
        vm.onDelete()
        testScheduler.advanceUntilIdle()

        assertThat(eventRepo.rows).doesNotContainKey(10L)
        // Read BEFORE the row went: the cascade has emptied the reminders by now.
        assertThat(reminderRepo.rows).isEmpty()
        val offer = offered()
        assertThat(offer.events).containsExactly(event)
        assertThat(offer.reminderMinutesByEventId).containsExactly(10L, listOf(15, 60))
        assertThat(offer.addedExclusion).isNull()
    }

    @Test
    fun `deleting a whole series offers the master and every override, master first`() = runTest(dispatcher) {
        val occurrence = at(2026, 7, 27, 9)
        val master = seedEvent(id = 10, title = "Sport", recurrence = weekly.excluding(occurrence))
        val override = seedOverride(id = 20, masterId = 10, originalStart = occurrence)
        reminder(1, eventId = 10, minutes = 30)
        reminder(2, eventId = 20, minutes = 5)

        val vm = viewModel(eventId = 10, occurrenceStart = at(2026, 8, 3, 9))
        testScheduler.advanceUntilIdle()
        vm.onDelete()
        testScheduler.advanceUntilIdle()
        vm.confirmScope(applyToSeries = true)
        testScheduler.advanceUntilIdle()

        assertThat(eventRepo.rows).isEmpty()
        val offer = offered()
        assertThat(offer.events).containsExactly(master, override).inOrder()
        assertThat(offer.reminderMinutesByEventId).containsExactly(10L, listOf(30), 20L, listOf(5))
        assertThat(offer.addedExclusion).isNull()
    }

    @Test
    fun `deleting one occurrence offers to lift that exclusion, and nothing else`() = runTest(dispatcher) {
        val occurrence = at(2026, 7, 27, 9)
        seedEvent(id = 10, title = "Sport", recurrence = weekly)
        reminder(1, eventId = 10, minutes = 30)

        val vm = viewModel(eventId = 10, occurrenceStart = occurrence)
        testScheduler.advanceUntilIdle()
        vm.onDelete()
        testScheduler.advanceUntilIdle()
        vm.confirmScope(applyToSeries = false)
        testScheduler.advanceUntilIdle()

        assertThat(eventRepo.rows[10]!!.recurrence!!.exDatesUtcMillis).containsExactly(occurrence)
        val offer = offered()
        assertThat(offer.events).isEmpty()
        assertThat(offer.reminderMinutesByEventId).isEmpty()
        assertThat(offer.addedExclusion).isEqualTo(SeriesExclusion(masterId = 10, occurrenceStartUtcMillis = occurrence))
    }

    @Test
    fun `deleting a moved occurrence offers it back WITHOUT lifting the exclusion its move had made`() =
        runTest(dispatcher) {
            val occurrence = at(2026, 7, 27, 9)
            seedEvent(id = 10, title = "Sport", recurrence = weekly.excluding(occurrence))
            val override = seedOverride(id = 20, masterId = 10, originalStart = occurrence)
            reminder(2, eventId = 20, minutes = 5)

            val vm = viewModel(eventId = 20)
            testScheduler.advanceUntilIdle()
            vm.onDelete()
            testScheduler.advanceUntilIdle()

            assertThat(eventRepo.rows).doesNotContainKey(20L)
            val offer = offered()
            assertThat(offer.events).containsExactly(override)
            assertThat(offer.reminderMinutesByEventId).containsExactly(20L, listOf(5))
            // Lifting it on undo would bring the series' own copy back beside the restored one.
            assertThat(offer.addedExclusion).isNull()
        }

    @Test
    fun `deleting a moved occurrence whose exclusion was missing offers to lift the one it added`() =
        runTest(dispatcher) {
            val occurrence = at(2026, 7, 27, 9)
            seedEvent(id = 10, title = "Sport", recurrence = weekly)
            seedOverride(id = 20, masterId = 10, originalStart = occurrence)

            val vm = viewModel(eventId = 20)
            testScheduler.advanceUntilIdle()
            vm.onDelete()
            testScheduler.advanceUntilIdle()

            assertThat(eventRepo.rows[10]!!.recurrence!!.exDatesUtcMillis).containsExactly(occurrence)
            assertThat(offered().addedExclusion).isEqualTo(SeriesExclusion(10, occurrence))
        }

    @Test
    fun `a deletion that fails offers nothing to undo`() = runTest(dispatcher) {
        seedEvent(id = 10)
        eventRepo.deleteThrows = true

        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()
        vm.onDelete()
        testScheduler.advanceUntilIdle()

        verify(exactly = 0) { deletionUndo.offer(any()) }
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
