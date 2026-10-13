package com.filestech.agenda_tech.data.repository

import androidx.room.withTransaction
import com.filestech.agenda_tech.core.di.IoDispatcher
import com.filestech.agenda_tech.data.local.db.AppDatabase
import com.filestech.agenda_tech.data.local.db.dao.EventDao
import com.filestech.agenda_tech.data.local.db.dao.ReminderDao
import com.filestech.agenda_tech.data.local.db.entity.ReminderEntity
import com.filestech.agenda_tech.domain.model.DeletedEvents
import com.filestech.agenda_tech.domain.repository.DeletionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeletionRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val eventDao: EventDao,
    private val reminderDao: ReminderDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DeletionRepository {

    override suspend fun restore(deleted: DeletedEvents): Unit = withContext(io) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            // Under their own ids: the widget, the alarms and an override's link to its master all name
            // events by id. The ids are free — AUTOINCREMENT never hands a deleted one out again — and
            // `insertNew` refuses rather than overwrites if one were not. `created_at` is the restore's
            // time: internal bookkeeping, as for a backup restore.
            deleted.events.forEach { eventDao.insertNew(it.toEntity(createdAt = now, updatedAt = now)) }

            // Fresh reminder ids, as a backup restore does: nothing names a reminder by its id.
            deleted.reminderMinutesByEventId.forEach { (eventId, minutes) ->
                minutes.forEach { reminderDao.upsert(ReminderEntity(eventId = eventId, minutesBefore = it)) }
            }

            // Lifted on the series as it is NOW, not written back as it was: it may have changed since.
            deleted.addedExclusion?.let { exclusion ->
                // The series gone meanwhile: nothing to bring the date back to. Said as a failure — the
                // transaction rolls back and the bar says so — rather than reported as restored
                // (external review, 2026-10-13).
                val row = checkNotNull(eventDao.getById(exclusion.masterId)) { "The series is gone" }
                val master = row.toDomain()
                val rule = master.recurrence ?: return@let
                if (exclusion.occurrenceStartUtcMillis in rule.exDatesUtcMillis) {
                    val lifted = rule.copy(exDatesUtcMillis = rule.exDatesUtcMillis - exclusion.occurrenceStartUtcMillis)
                    eventDao.upsert(master.copy(recurrence = lifted).toEntity(createdAt = row.createdAt, updatedAt = now))
                }
            }
        }
    }
}
