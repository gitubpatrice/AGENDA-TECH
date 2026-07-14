package com.filestech.agenda_tech.data.repository

import com.filestech.agenda_tech.data.local.db.dao.CalendarDao
import com.filestech.agenda_tech.di.IoDispatcher
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarRepositoryImpl @Inject constructor(
    private val dao: CalendarDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CalendarRepository {

    override fun observeAll(): Flow<List<Calendar>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }.flowOn(io)

    override fun observeVisible(): Flow<List<Calendar>> =
        dao.observeVisible().map { rows -> rows.map { it.toDomain() } }.flowOn(io)

    override suspend fun getById(id: Long): Calendar? = withContext(io) {
        dao.getById(id)?.toDomain()
    }

    override suspend fun findBySourceId(sourceId: String): Calendar? = withContext(io) {
        dao.findBySourceId(sourceId)?.toDomain()
    }

    override suspend fun count(): Int = withContext(io) { dao.count() }

    override suspend fun upsert(calendar: Calendar): Long = withContext(io) {
        // Preserve the original createdAt on edit; stamp a fresh one on insert.
        val createdAt = if (calendar.id != 0L) {
            dao.getById(calendar.id)?.createdAt ?: System.currentTimeMillis()
        } else {
            System.currentTimeMillis()
        }
        dao.upsert(calendar.toEntity(createdAt))
    }

    override suspend fun setVisibility(id: Long, visible: Boolean) = withContext(io) {
        dao.setVisibility(id, visible)
    }

    override suspend fun delete(id: Long) = withContext(io) {
        dao.delete(id)
    }
}
