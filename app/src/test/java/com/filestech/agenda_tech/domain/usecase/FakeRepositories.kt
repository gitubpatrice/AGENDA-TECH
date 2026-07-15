package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.DeviceCalendar
import com.filestech.agenda_tech.domain.model.DeviceEvent
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.repository.BackupRepository
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.repository.DeviceCalendarRepository
import com.filestech.agenda_tech.domain.repository.EventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory doubles shared by the import use-case tests. They mimic the real persistence semantics
 * (id generation, insert-or-update, the DAO's exact delete predicate) so a test can't pass against a
 * fake that is kinder than the database.
 */

/** In-memory device calendar source — the point of the domain interface: no Android needed. */
internal class FakeDeviceCalendars(
    private val calendars: List<DeviceCalendar> = emptyList(),
    var events: List<DeviceEvent> = emptyList(),
) : DeviceCalendarRepository {
    override suspend fun listCalendars(): List<DeviceCalendar> = calendars
    override suspend fun readEvents(deviceCalendarId: Long): List<DeviceEvent> = events
}

internal class FakeCalendarRepository : CalendarRepository {
    val stored = mutableListOf<Calendar>()
    private var nextId = 1L

    override fun observeAll(): Flow<List<Calendar>> = flowOf(stored.toList())
    override fun observeVisible(): Flow<List<Calendar>> = flowOf(stored.filter { it.isVisible })
    override suspend fun getById(id: Long): Calendar? = stored.firstOrNull { it.id == id }
    override suspend fun findBySourceId(sourceId: String): Calendar? = stored.firstOrNull { it.sourceId == sourceId }
    override suspend fun count(): Int = stored.size

    override suspend fun upsert(calendar: Calendar): Long {
        val id = if (calendar.id == 0L) nextId++ else calendar.id
        stored.removeAll { it.id == id }
        stored += calendar.copy(id = id)
        return id
    }

    override suspend fun setVisibility(id: Long, visible: Boolean) = Unit
    override suspend fun delete(id: Long) { stored.removeAll { it.id == id } }

    override suspend fun promoteDefaultAndDelete(promoteId: Long?, deleteId: Long) {
        promoteId?.let { id ->
            val i = stored.indexOfFirst { it.id == id }
            if (i >= 0) stored[i] = stored[i].copy(isDefault = true)
        }
        stored.removeAll { it.id == deleteId }
    }

    // Mirrors the DAO predicate exactly: only rows that carry a source_id, never a hand-made calendar.
    override suspend fun deleteImported() { stored.removeAll { !it.isDefault && it.sourceId != null } }
}

internal class FakeEventRepository : EventRepository {
    /** Rows keyed by id, mimicking the DB's insert-or-update semantics. */
    val rows = mutableMapOf<Long, Event>()
    private var nextId = 100L

    override fun observeForExpansion(windowStartUtcMillis: Long, windowEndUtcMillis: Long): Flow<List<Event>> =
        flowOf(rows.values.toList())

    override fun observeByCalendar(calendarId: Long): Flow<List<Event>> =
        flowOf(rows.values.filter { it.calendarId == calendarId })

    override fun observeOverrides(): Flow<List<Event>> =
        flowOf(rows.values.filter { it.recurrenceParentId != null })
    override suspend fun getById(id: Long): Event? = rows[id]
    override suspend fun getAll(): List<Event> = rows.values.toList()
    override fun observeAll(): Flow<List<Event>> = flowOf(rows.values.sortedBy { it.startUtcMillis })
    override suspend fun deleteOverridesForParent(parentId: Long) = Unit
    override suspend fun deleteSeriesAtomic(parentId: Long) = Unit
    override suspend fun upsertAndDelete(event: Event, deleteId: Long) = Unit

    /** Mirrors the real implementation: writes the override AND the master's new EXDATE together. */
    override suspend fun upsertOverrideAtomic(
        override: Event,
        masterId: Long,
        originalStartUtcMillis: Long,
    ): Long {
        val id = upsert(override)
        val master = rows[masterId]
        val rule = master?.recurrence
        if (rule != null && originalStartUtcMillis !in rule.exDatesUtcMillis) {
            rows[masterId] = master.copy(
                recurrence = rule.copy(exDatesUtcMillis = rule.exDatesUtcMillis + originalStartUtcMillis),
            )
        }
        return id
    }

    override suspend fun upsert(event: Event): Long {
        val id = if (event.id == 0L) nextId++ else event.id
        rows[id] = event.copy(id = id)
        return id
    }

    /**
     * Mirrors EventRepositoryImpl: an import never carries address/GPS (no calendar source has them),
     * so the locally-typed place details of a row being updated are merged back instead of wiped.
     */
    override suspend fun upsertAll(events: List<Event>) {
        events.forEach { event ->
            val kept = rows[event.id]
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
            upsert(merged)
        }
    }

    override suspend fun sourceUidMap(calendarId: Long): Map<String, Long> =
        rows.values
            .filter { it.calendarId == calendarId && it.sourceUid != null }
            .associate { it.sourceUid!! to it.id }

    override suspend fun delete(id: Long) { rows.remove(id) }
}

/**
 * In-memory backup store. [replaceAll] mirrors the real implementation's contract — it wipes before
 * inserting, and drops reminders naming an event the file does not contain (the FK the DB enforces).
 */
internal class FakeBackupRepository : BackupRepository {
    val calendars = mutableListOf<Calendar>()
    val events = mutableListOf<Event>()
    val reminders = mutableMapOf<Long, List<Int>>()
    var failOnWrite = false

    override suspend fun reminderMinutesByEventId(): Map<Long, List<Int>> = reminders.toMap()

    override suspend fun replaceAll(
        calendars: List<Calendar>,
        events: List<Event>,
        remindersByEventId: Map<Long, List<Int>>,
    ) {
        if (failOnWrite) error("simulated storage failure")
        val knownEventIds = events.mapTo(HashSet()) { it.id }
        this.calendars.clear(); this.calendars.addAll(calendars)
        this.events.clear(); this.events.addAll(events)
        this.reminders.clear()
        this.reminders.putAll(remindersByEventId.filterKeys { it in knownEventIds })
    }
}
