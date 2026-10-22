package com.filestech.agenda_tech.ui.screens.editor

import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** What the editor hands to "Share": the event the agenda holds, at the occurrence the user opened. */
internal class EventEditorShareTest : EventEditorTestBase() {

    @Test
    fun `an event is shared as the agenda holds it, not as the form was retyped`() = runTest(dispatcher) {
        val stored = seedEvent(id = 10, title = "Dentiste")

        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()
        vm.onTitleChange("Dentiste — pas encore enregistré")
        val source = vm.shareSource()!!

        assertThat(source.event).isEqualTo(stored)
        assertThat(source.startUtcMillis).isEqualTo(stored.startUtcMillis)
        assertThat(source.endUtcMillis).isEqualTo(stored.endUtcMillis)
    }

    @Test
    fun `an occurrence of a series is shared at its own date, the one the editor shows`() = runTest(dispatcher) {
        seedEvent(id = 10, title = "Sport", recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY))
        val occurrence = at(2026, 7, 27, 9)

        val vm = viewModel(eventId = 10, occurrenceStart = occurrence)
        testScheduler.advanceUntilIdle()
        val source = vm.shareSource()!!

        assertThat(source.startUtcMillis).isEqualTo(occurrence)
        assertThat(source.endUtcMillis).isEqualTo(at(2026, 7, 27, 10))
    }

    @Test
    fun `a new event, never saved, has nothing to share`() = runTest(dispatcher) {
        val vm = viewModel()
        testScheduler.advanceUntilIdle()

        assertThat(vm.shareSource()).isNull()
    }

    @Test
    fun `a moved occurrence goes as its series, filed under the series`() = runTest(dispatcher) {
        // External review, 2026-10-13: the .ics held the moved occurrence alone — an instance of a series
        // it did not carry, which the receiving calendar drops or imports on its own.
        val occurrence = at(2026, 7, 27, 9)
        seedEvent(id = 10, title = "Sport", recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY).excluding(occurrence))
        eventRepo.rows[11] = Event(
            id = 11,
            calendarId = 1,
            title = "Sport déplacé",
            startUtcMillis = occurrence + HOUR,
            endUtcMillis = occurrence + 2 * HOUR,
            timeZoneId = zone.id,
            recurrenceParentId = 10,
            originalStartUtcMillis = occurrence,
        )

        val vm = viewModel(eventId = 11)
        testScheduler.advanceUntilIdle()
        vm.calendarFileToShare(vm.shareSource()!!)

        val content = slot<String>()
        coVerify(exactly = 1) { sharedFiles.calendarFile(10, "Sport", capture(content)) }
        assertThat(content.captured).contains("RRULE:")
        assertThat(content.captured).contains("SUMMARY:Sport déplacé")
    }

    @Test
    fun `a shared file names the event by an identity a later save keeps`() = runTest(dispatcher) {
        // 2026-10-22 — the event gets its UID when it first leaves the app, while the editor still holds
        // the null it loaded: saving after sharing wrote that null over it, and the shared file named a
        // UID the agenda no longer knew.
        seedEvent(id = 10, title = "Dentiste")
        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()

        vm.calendarFileToShare(vm.shareSource()!!)
        val given = eventRepo.rows.getValue(10).sourceUid
        vm.onTitleChange("Dentiste reporté")
        vm.onSave()
        testScheduler.advanceUntilIdle()

        val content = slot<String>()
        coVerify(exactly = 1) { sharedFiles.calendarFile(10, "Dentiste", capture(content)) }
        assertThat(given).isNotNull()
        assertThat(content.captured).contains("UID:${given!!.removePrefix("ics:")}")
        assertThat(eventRepo.rows.getValue(10).title).isEqualTo("Dentiste reporté")
        assertThat(eventRepo.rows.getValue(10).sourceUid).isEqualTo(given)
    }

    @Test
    fun `a share asked as a deletion begins gives up, so no copy outlives the event`() = runTest(dispatcher) {
        // External review, 2026-10-13: written just after the deletion had cleared the shares, the copy
        // stayed behind its event.
        seedEvent(id = 10)
        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()
        val source = vm.shareSource()!!

        vm.onDelete()
        val shared = vm.calendarFileToShare(source)
        testScheduler.advanceUntilIdle()

        assertThat(shared).isNull()
        coVerify(exactly = 0) { sharedFiles.calendarFile(any(), any(), any()) }
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
