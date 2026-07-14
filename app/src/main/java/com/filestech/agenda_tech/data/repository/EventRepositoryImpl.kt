package com.filestech.agenda_tech.data.repository

import com.filestech.agenda_tech.data.local.db.dao.EventDao
import com.filestech.agenda_tech.di.IoDispatcher
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.repository.EventRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventRepositoryImpl @Inject constructor(
    private val dao: EventDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : EventRepository {

    override fun observeInRange(startUtcMillis: Long, endUtcMillis: Long): Flow<List<Event>> =
        dao.observeInRange(startUtcMillis, endUtcMillis)
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(io)

    override fun observeForExpansion(windowStartUtcMillis: Long, windowEndUtcMillis: Long): Flow<List<Event>> =
        dao.observeForExpansion(windowStartUtcMillis, windowEndUtcMillis)
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(io)

    override fun observeByCalendar(calendarId: Long): Flow<List<Event>> =
        dao.observeByCalendar(calendarId)
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(io)

    override suspend fun getById(id: Long): Event? = withContext(io) {
        dao.getById(id)?.toDomain()
    }

    override suspend fun getAll(): List<Event> = withContext(io) {
        dao.getAll().map { it.toDomain() }
    }

    override fun observeOverrides(): Flow<List<Event>> =
        dao.observeOverrides()
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(io)

    override suspend fun deleteOverridesForParent(parentId: Long) = withContext(io) {
        dao.deleteOverridesForParent(parentId)
    }

    override suspend fun upsert(event: Event): Long = withContext(io) {
        val now = System.currentTimeMillis()
        // Preserve the original createdAt on edit; stamp a fresh one on insert.
        val createdAt = if (event.id != 0L) {
            dao.getById(event.id)?.createdAt ?: now
        } else {
            now
        }
        dao.upsert(event.toEntity(createdAt = createdAt, updatedAt = now))
    }

    override suspend fun upsertAll(events: List<Event>) = withContext(io) {
        // Import path: every event is new, so stamp a single timestamp and insert atomically.
        val now = System.currentTimeMillis()
        dao.upsertAll(events.map { it.toEntity(createdAt = now, updatedAt = now) })
    }

    override suspend fun delete(id: Long) = withContext(io) {
        dao.delete(id)
    }
}
