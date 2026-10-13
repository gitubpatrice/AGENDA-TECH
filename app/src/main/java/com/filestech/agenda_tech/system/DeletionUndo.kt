package com.filestech.agenda_tech.system

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.filestech.agenda_tech.core.di.ApplicationScope
import com.filestech.agenda_tech.core.di.IoDispatcher
import com.filestech.agenda_tech.domain.model.DeletedEvents
import com.filestech.agenda_tech.domain.repository.DeletionRepository
import com.filestech.agenda_tech.system.alarm.ReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** A deletion whose "Undo" is on offer. [id] tells two deletions apart, even of the same event. */
data class PendingDeletion(val id: Long, val deleted: DeletedEvents, val offeredAtMillis: Long)

enum class UndoResult {
    /** Everything is back. */
    RESTORED,

    /** Nothing was written: the agenda no longer allowed it (the event's calendar deleted meanwhile). */
    FAILED,

    /** Already undone, dismissed or replaced by a newer deletion — nothing done. */
    GONE,
}

/**
 * The last deletion, and its "Undo".
 *
 * Held here, for the whole app, and not by a screen: the editor that deletes closes at once, and the
 * offer shows on whichever screen it returns to — a calendar view or the search. One deletion at a
 * time: a new one replaces the offer, as a second snackbar replaces the first.
 *
 * The restore runs on [ApplicationScope]: once "Undo" is tapped it must finish even if the user leaves
 * the screen in the same second. The repository and the scheduler are resolved inside it, never at
 * construction — resolving them opens the encrypted database, and this object is built on the main
 * thread (the rule [AgendaChangeNotifier] states, for the same reason).
 */
@Singleton
class DeletionUndo @Inject constructor(
    private val deletions: Provider<DeletionRepository>,
    private val reminderScheduler: Provider<ReminderScheduler>,
    private val agendaChanged: AgendaChangeNotifier,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    @VisibleForTesting
    internal var clock: () -> Long = SystemClock::elapsedRealtime

    private val nextId = AtomicLong()
    private val _pending = MutableStateFlow<PendingDeletion?>(null)
    val pending: StateFlow<PendingDeletion?> = _pending.asStateFlow()

    /** Offers to undo [deleted]. Called once the deletion is written, never before. */
    fun offer(deleted: DeletedEvents) {
        if (deleted.isEmpty) return
        _pending.value = PendingDeletion(nextId.incrementAndGet(), deleted, clock())
    }

    /**
     * Whether [pending] is still worth offering. A screen that comes back later — after opening another
     * event, say — must not bring up "Event deleted" about something long past.
     */
    fun isFresh(pending: PendingDeletion): Boolean = clock() - pending.offeredAtMillis < OFFER_WINDOW_MS

    /** The offer ran out or was swiped away: it will not come back. */
    fun dismiss(pending: PendingDeletion) {
        _pending.compareAndSet(pending, null)
    }

    /**
     * Puts back what [pending] removed. Claimed first, atomically: two screens showing the same offer
     * during a transition, or a double tap, restore it once — the second claim finds nothing.
     */
    suspend fun undo(pending: PendingDeletion): UndoResult {
        if (!_pending.compareAndSet(pending, null)) return UndoResult.GONE
        return appScope.async(io) { restore(pending.deleted) }.await()
    }

    private suspend fun restore(deleted: DeletedEvents): UndoResult {
        try {
            deletions.get().restore(deleted)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Timber.w(t, "Undo: the deleted events could not be restored")
            return UndoResult.FAILED
        }
        // Restored: an alarm that fails to re-arm is logged, it does not turn the undo into a failure.
        val scheduler = reminderScheduler.get()
        val rearm = deleted.events.map { it.id } + listOfNotNull(deleted.addedExclusion?.masterId)
        rearm.distinct().forEach { eventId ->
            try {
                scheduler.rescheduleEvent(eventId)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Timber.w(t, "Undo: reminders of a restored event could not be re-armed")
            }
        }
        agendaChanged.onAgendaChanged(rearmReminders = false)
        return UndoResult.RESTORED
    }

    companion object {
        /**
         * How long after the deletion the offer may still be SHOWN. Checked before showing it, never
         * while it is shown: a snackbar on screen stays usable for as long as the system keeps it there,
         * which accessibility settings can lengthen well past this.
         */
        const val OFFER_WINDOW_MS = 30_000L
    }
}
