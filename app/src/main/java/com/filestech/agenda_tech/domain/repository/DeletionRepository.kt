package com.filestech.agenda_tech.domain.repository

import com.filestech.agenda_tech.domain.model.DeletedEvents

/** Puts back what a deletion took away — the "Undo" offered after deleting an event. */
interface DeletionRepository {

    /**
     * Restores [deleted] in one transaction, all or nothing: the rows with their own ids, their
     * reminders, and the lifting of the exclusion the deletion added to a series.
     *
     * Throws when the agenda no longer allows it — the event's calendar deleted in the meantime, or, for
     * a deleted occurrence, its series gone or no longer excluding its date — and then writes nothing.
     */
    suspend fun restore(deleted: DeletedEvents)
}
