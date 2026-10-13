package com.filestech.agenda_tech.system

import com.filestech.agenda_tech.domain.model.DeletedEvents
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.SeriesExclusion
import com.filestech.agenda_tech.domain.repository.DeletionRepository
import com.filestech.agenda_tech.system.alarm.ReminderScheduler
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import javax.inject.Provider

class DeletionUndoTest {

    private val dispatcher = StandardTestDispatcher()
    private val restored = mutableListOf<DeletedEvents>()
    private var restoreFails = false
    private val repository = object : DeletionRepository {
        override suspend fun restore(deleted: DeletedEvents) {
            if (restoreFails) error("the calendar was deleted meanwhile")
            restored += deleted
        }
    }
    private val scheduler: ReminderScheduler = mockk(relaxed = true)
    private val agendaChanged: AgendaChangeNotifier = mockk(relaxed = true)
    private var now = 1_000L

    private val undo = DeletionUndo(
        deletions = Provider { repository },
        reminderScheduler = Provider { scheduler },
        agendaChanged = agendaChanged,
        appScope = CoroutineScope(SupervisorJob() + dispatcher),
        io = dispatcher,
    ).also { it.clock = { now } }

    private val event = Event(id = 10, calendarId = 1, title = "Dentiste", startUtcMillis = 0, endUtcMillis = 1, timeZoneId = "UTC")
    private val deleted = DeletedEvents(
        events = listOf(event),
        reminderMinutesByEventId = mapOf(10L to listOf(15)),
        addedExclusion = SeriesExclusion(masterId = 7, occurrenceStartUtcMillis = 100),
    )

    @Test
    fun `undo restores what was offered, re-arms its alarms and redraws the widget`() = runTest(dispatcher) {
        undo.offer(deleted)
        val offer = undo.pending.value!!

        assertThat(undo.undo(offer)).isEqualTo(UndoResult.RESTORED)

        assertThat(restored).containsExactly(deleted)
        assertThat(undo.pending.value).isNull()
        // The restored event, and the series whose exclusion was lifted.
        coVerify { scheduler.rescheduleEvent(10) }
        coVerify { scheduler.rescheduleEvent(7) }
        verify { agendaChanged.onAgendaChanged(rearmReminders = false) }
    }

    @Test
    fun `a second undo of the same offer restores nothing`() = runTest(dispatcher) {
        undo.offer(deleted)
        val offer = undo.pending.value!!

        assertThat(undo.undo(offer)).isEqualTo(UndoResult.RESTORED)
        // A double tap, or two screens showing the same offer during a transition.
        assertThat(undo.undo(offer)).isEqualTo(UndoResult.GONE)

        assertThat(restored).hasSize(1)
    }

    @Test
    fun `a newer deletion replaces the offer, and the old one can no longer be undone`() = runTest(dispatcher) {
        undo.offer(deleted)
        val first = undo.pending.value!!
        val newer = deleted.copy(events = listOf(event.copy(id = 11)))
        undo.offer(newer)

        assertThat(undo.undo(first)).isEqualTo(UndoResult.GONE)
        assertThat(restored).isEmpty()
        assertThat(undo.pending.value!!.deleted).isEqualTo(newer)
    }

    @Test
    fun `the same deletion offered twice is two offers, not one`() = runTest(dispatcher) {
        undo.offer(deleted)
        val first = undo.pending.value!!
        undo.offer(deleted)

        // Equal content, different deletion: the first must not claim the second.
        assertThat(undo.undo(first)).isEqualTo(UndoResult.GONE)
        assertThat(restored).isEmpty()
    }

    @Test
    fun `a dismissed offer cannot be undone`() = runTest(dispatcher) {
        undo.offer(deleted)
        val offer = undo.pending.value!!
        undo.dismiss(offer)

        assertThat(undo.pending.value).isNull()
        assertThat(undo.undo(offer)).isEqualTo(UndoResult.GONE)
        assertThat(restored).isEmpty()
    }

    @Test
    fun `dismissing an old offer leaves the newer one alone`() = runTest(dispatcher) {
        undo.offer(deleted)
        val old = undo.pending.value!!
        undo.offer(deleted.copy(addedExclusion = null))

        undo.dismiss(old)

        assertThat(undo.pending.value).isNotNull()
    }

    @Test
    fun `a restore the database refuses says so, and re-arms nothing`() = runTest(dispatcher) {
        restoreFails = true
        undo.offer(deleted)

        assertThat(undo.undo(undo.pending.value!!)).isEqualTo(UndoResult.FAILED)

        coVerify(exactly = 0) { scheduler.rescheduleEvent(any()) }
        verify(exactly = 0) { agendaChanged.onAgendaChanged(any()) }
    }

    @Test
    fun `an offer is shown only while it is recent`() {
        undo.offer(deleted)
        val offer = undo.pending.value!!

        now += DeletionUndo.OFFER_WINDOW_MS - 1
        assertThat(undo.isFresh(offer)).isTrue()
        now += 1
        assertThat(undo.isFresh(offer)).isFalse()
    }

    @Test
    fun `a deletion that removed nothing offers nothing`() {
        undo.offer(DeletedEvents())

        assertThat(undo.pending.value).isNull()
    }
}
