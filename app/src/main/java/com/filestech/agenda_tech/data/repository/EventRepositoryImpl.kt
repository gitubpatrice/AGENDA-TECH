package com.filestech.agenda_tech.data.repository

import androidx.room.withTransaction
import com.filestech.agenda_tech.data.local.db.dao.EventDao
import com.filestech.agenda_tech.core.di.IoDispatcher
import com.filestech.agenda_tech.data.local.db.AppDatabase
import com.filestech.agenda_tech.domain.ics.newIcsSourceUid
import com.filestech.agenda_tech.domain.model.AgendaStats
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
    private val db: AppDatabase,
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

    override fun observeStats(): Flow<AgendaStats> = dao.observeStats()
        .map { AgendaStats(eventCount = it.eventCount, lastChangeAtUtcMillis = it.lastChangeAtUtcMillis) }
        .flowOn(io)

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

    override suspend fun excludeOccurrence(
        masterId: Long,
        occurrenceStartUtcMillis: Long,
        deleteId: Long?,
    ): Boolean = withContext(io) {
        db.withTransaction {
            deleteId?.let { dao.delete(it) }
            val row = dao.getById(masterId)
            val master = row?.toDomain()
            val rule = master?.recurrence
            if (row == null || rule == null || occurrenceStartUtcMillis in rule.exDatesUtcMillis) {
                false
            } else {
                val excluded = master.copy(recurrence = rule.excluding(occurrenceStartUtcMillis))
                dao.upsert(excluded.toEntity(createdAt = row.createdAt, updatedAt = System.currentTimeMillis()))
                true
            }
        }
    }

    override suspend fun upsertOverrideAtomic(
        override: Event,
        masterId: Long,
        originalStartUtcMillis: Long,
    ): Long = withContext(io) {
        val now = System.currentTimeMillis()
        // One transaction with the read, as in [upsert]: the master is written back whole, and an identity
        // a share gave it in between would otherwise be written over.
        db.withTransaction {
            val masterRow = dao.getById(masterId)
            val master = masterRow?.toDomain()
            val rule = master?.recurrence
            // Null when there is nothing to rewrite: the master is gone, is not recurring, or already
            // excludes this instant (a re-save of the same override — `excluding` is idempotent).
            val excluded = rule?.excluding(originalStartUtcMillis)
            val updatedMaster = if (excluded != null && excluded != rule) {
                master.copy(recurrence = excluded).toEntity(createdAt = masterRow.createdAt, updatedAt = now)
            } else {
                null
            }
            dao.upsertOverrideAndMaster(
                override = override.toEntity(createdAt = now, updatedAt = now),
                master = updatedMaster,
            )
        }
    }

    override suspend fun upsert(event: Event): Long = withContext(io) {
        val now = System.currentTimeMillis()
        // One transaction: the row read here is the row written over, with no share landing in between.
        val rowId = db.withTransaction {
            val existing = if (event.id != 0L) dao.getById(event.id) else null
            // Preserve the original createdAt on edit; stamp a fresh one on insert.
            val createdAt = existing?.createdAt ?: now
            // An update never takes an event's identity away (2026-10-22). The editor writes back the uid
            // it loaded; "Share as .ics" may have given the event one since, and writing the stale null
            // over it would leave the shared file naming a UID the agenda no longer knows.
            val kept = if (event.sourceUid == null && existing?.sourceUid != null) {
                event.copy(sourceUid = existing.sourceUid)
            } else {
                event
            }
            dao.upsert(kept.toEntity(createdAt = createdAt, updatedAt = now))
        }
        // Room's @Upsert returns the new rowid on INSERT but **-1 on UPDATE**. The contract above
        // promises "the event's id", so answer that — never hand a caller the raw DAO value.
        //
        // This is not hypothetical: leaking the -1 crashed the editor on every save of an existing
        // event that had a reminder (`Reminder(eventId = -1)` → FOREIGN KEY constraint failed). The
        // DAO carried a comment telling callers not to use its return this way; a warning that has to
        // be remembered at each call site is a bug waiting for a new caller. Fixed here instead, once,
        // where the -1 can no longer escape.
        if (event.id != 0L) event.id else rowId
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

    override suspend fun withIcsIdentities(events: List<Event>): List<Event> = withContext(io) {
        val minted = events.filter { it.sourceUid == null }.associate { it.id to newIcsSourceUid() }
        if (minted.isEmpty()) return@withContext events
        val now = System.currentTimeMillis()
        // Conditional, then read back: a row given a UID meanwhile keeps it, and the file must carry THAT
        // one. A row deleted meanwhile reads back null — the file can still carry the minted one.
        val held = db.withTransaction {
            minted.mapValues { (id, uid) ->
                dao.assignSourceUid(id, uid, now)
                dao.getById(id)?.sourceUid ?: uid
            }
        }
        events.map { event -> if (event.sourceUid != null) event else event.copy(sourceUid = held.getValue(event.id)) }
    }

    override suspend fun sourceUidGroups(calendarId: Long): Map<String, List<Long>> = withContext(io) {
        dao.sourceUidRows(calendarId)
            .groupBy({ it.sourceUid }, { it.id })
            .mapValues { (_, ids) -> ids.sorted() }
    }

    override suspend fun delete(id: Long) = withContext(io) {
        dao.delete(id)
    }
}
