package com.filestech.agenda_tech.data.repository

import com.filestech.agenda_tech.data.local.db.dao.ReminderDao
import com.filestech.agenda_tech.di.IoDispatcher
import com.filestech.agenda_tech.domain.model.Reminder
import com.filestech.agenda_tech.domain.repository.ReminderRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderRepositoryImpl @Inject constructor(
    private val dao: ReminderDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ReminderRepository {

    override fun observeForEvent(eventId: Long): Flow<List<Reminder>> =
        dao.observeForEvent(eventId)
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(io)

    override suspend fun getForEvent(eventId: Long): List<Reminder> = withContext(io) {
        dao.getForEvent(eventId).map { it.toDomain() }
    }

    override suspend fun getById(id: Long): Reminder? = withContext(io) {
        dao.getById(id)?.toDomain()
    }

    override suspend fun getAll(): List<Reminder> = withContext(io) {
        dao.getAll().map { it.toDomain() }
    }

    override suspend fun upsert(reminder: Reminder): Long = withContext(io) {
        val rowId = dao.upsert(reminder.toEntity())
        // Same trap as EventRepositoryImpl.upsert: -1 on UPDATE. Nothing links to a reminder id today,
        // but the three repositories make the same promise and must keep it the same way.
        if (reminder.id != 0L) reminder.id else rowId
    }

    override suspend fun deleteForEvent(eventId: Long) = withContext(io) {
        dao.deleteForEvent(eventId)
    }

    override suspend fun delete(id: Long) = withContext(io) {
        dao.delete(id)
    }
}
