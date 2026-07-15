package com.filestech.agenda_tech.data.repository

import androidx.room.withTransaction
import com.filestech.agenda_tech.data.local.db.AppDatabase
import com.filestech.agenda_tech.data.local.db.dao.BackupDao
import com.filestech.agenda_tech.data.local.db.dao.ReminderDao
import com.filestech.agenda_tech.data.local.db.entity.ReminderEntity
import com.filestech.agenda_tech.di.IoDispatcher
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.repository.BackupRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val backupDao: BackupDao,
    private val reminderDao: ReminderDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : BackupRepository {

    override suspend fun reminderMinutesByEventId(): Map<Long, List<Int>> = withContext(io) {
        reminderDao.getAll()
            .groupBy { it.eventId }
            .mapValues { (_, rows) -> rows.map { it.minutesBefore }.sorted() }
    }

    override suspend fun replaceAll(
        calendars: List<Calendar>,
        events: List<Event>,
        remindersByEventId: Map<Long, List<Int>>,
    ) = withContext(io) {
        val now = System.currentTimeMillis()
        // Timestamps are restore-time, not export-time: created_at/updated_at are internal bookkeeping
        // the user never sees, and carrying them in the file would add fields to keep in sync for no
        // benefit. Ids are what must survive — those come from the file.
        val calendarRows = calendars.map { it.toEntity(createdAt = now) }
        val eventRows = events.map { it.toEntity(createdAt = now, updatedAt = now) }

        // Reminders are rebuilt from the event's minutes list, with fresh ids: nothing references a
        // reminder id, so preserving them would be pointless precision.
        val knownEventIds = events.mapTo(HashSet()) { it.id }
        val reminderRows = remindersByEventId.asSequence()
            // A file naming an event it doesn't contain would violate the FK and abort the whole
            // restore. Dropping the orphan costs one reminder; aborting costs the user their agenda.
            .filter { (eventId, _) -> eventId in knownEventIds }
            .flatMap { (eventId, minutes) ->
                minutes.asSequence().map { ReminderEntity(eventId = eventId, minutesBefore = it) }
            }
            .toList()

        db.withTransaction {
            backupDao.deleteAllReminders()
            backupDao.deleteAllEvents()
            backupDao.deleteAllCalendars()
            backupDao.insertCalendars(calendarRows)
            backupDao.insertEvents(eventRows)
            backupDao.insertReminders(reminderRows)
        }
    }
}
