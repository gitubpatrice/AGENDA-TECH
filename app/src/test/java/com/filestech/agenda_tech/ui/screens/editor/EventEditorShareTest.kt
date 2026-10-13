package com.filestech.agenda_tech.ui.screens.editor

import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.google.common.truth.Truth.assertThat
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
}
