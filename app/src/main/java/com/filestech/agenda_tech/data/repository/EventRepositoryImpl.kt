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

    override fun observeAll(): Flow<List<Event>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }.flowOn(io)

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

    override suspend fun deleteSeriesAtomic(parentId: Long) = withContext(io) {
        dao.deleteSeriesAtomic(parentId)
    }

    override suspend fun upsertAndDelete(event: Event, deleteId: Long) = withContext(io) {
        val now = System.currentTimeMillis()
        val createdAt = if (event.id != 0L) dao.getById(event.id)?.createdAt ?: now else now
        dao.upsertAndDelete(event.toEntity(createdAt = createdAt, updatedAt = now), deleteId)
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
        // Import path: write atomically. Events carrying an existing id (matched by source uid) are
        // updated in place — preserve their original createdAt; new rows get `now`. updatedAt is now.
        val now = System.currentTimeMillis()
        val existingIds = events.mapNotNull { it.id.takeIf { id -> id != 0L } }
        val createdAtById = if (existingIds.isEmpty()) {
            emptyMap()
        } else {
            dao.createdAtByIds(existingIds).associate { it.id to it.createdAt }
        }
        // No calendar source carries an address or GPS coordinates: they only ever come from the user.
        // An @Upsert rewrites the whole row, so without reading them back a refresh would silently
        // wipe what the user typed. Merge them onto the rows we are updating.
        val placeById = if (existingIds.isEmpty()) {
            emptyMap()
        } else {
            dao.placeDetailsByIds(existingIds).associateBy { it.id }
        }
        val entities = events.map { event ->
            val kept = placeById[event.id]
            val merged = if (kept == null) {
                event
            } else {
                event.copy(
                    address = kept.address,
                    postalCode = kept.postalCode,
                    city = kept.city,
                    gpsCoordinates = kept.gpsCoordinates,
                )
            }
            merged.toEntity(createdAt = createdAtById[event.id] ?: now, updatedAt = now)
        }
        dao.upsertAll(entities)
    }

    override suspend fun sourceUidMap(calendarId: Long): Map<String, Long> = withContext(io) {
        dao.sourceUidRows(calendarId).associate { it.sourceUid to it.id }
    }

    override suspend fun delete(id: Long) = withContext(io) {
        dao.delete(id)
    }
}
