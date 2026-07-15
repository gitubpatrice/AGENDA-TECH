package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.domain.model.AgendaStats
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.DeviceCalendar
import com.filestech.agenda_tech.domain.model.DeviceEvent
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.repository.BackupRepository
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.repository.DeviceCalendarRepository
import com.filestech.agenda_tech.domain.repository.EventRepository
import com.filestech.agenda_tech.domain.model.Reminder
import com.filestech.agenda_tech.domain.repository.ReminderRepository
import com.filestech.agenda_tech.domain.repository.SettingsRepository
import com.filestech.agenda_tech.domain.settings.AppSettings
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
    /** Mirrors the real query: count, and the most recent change (0 when empty). */
    override fun observeStats(): Flow<AgendaStats> = flowOf(
        AgendaStats(
            eventCount = rows.size,
            lastChangeAtUtcMillis = lastChangeAtUtcMillis,
        ),
    )

    /** Stands in for `MAX(updated_at)`, which a fake holding domain models cannot compute. */
    var lastChangeAtUtcMillis: Long = 0L
    override suspend fun deleteOverridesForParent(parentId: Long) {
        rows.values.filter { it.recurrenceParentId == parentId }.forEach { rows.remove(it.id) }
    }

    /** Mirrors the real DAO: the master AND every override of it go together. */
    override suspend fun deleteSeriesAtomic(parentId: Long) {
        deleteOverridesForParent(parentId)
        rows.remove(parentId)
    }

    /** Mirrors the real DAO: writes [event] and removes [deleteId] in one go. */
    override suspend fun upsertAndDelete(event: Event, deleteId: Long) {
        upsert(event)
        rows.remove(deleteId)
    }

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

/** In-memory reminders, keyed by id like the DB. */
internal class FakeReminderRepository : ReminderRepository {
    val rows = mutableMapOf<Long, Reminder>()
    private var nextId = 1000L

    /**
     * Ordered trace of the writes. Lets a test assert the *sequence* across this fake and the mocked
     * scheduler — "cancel the alarms before replacing the rows they point at" is an ordering rule, and
     * verifying each side alone would miss the very inversion it guards against.
     */
    val callLog = mutableListOf<String>()

    override fun observeForEvent(eventId: Long): Flow<List<Reminder>> =
        flowOf(rows.values.filter { it.eventId == eventId }.sortedBy { it.minutesBefore })

    override suspend fun getForEvent(eventId: Long): List<Reminder> =
        rows.values.filter { it.eventId == eventId }.sortedBy { it.minutesBefore }

    override suspend fun getById(id: Long): Reminder? = rows[id]
    override suspend fun getAll(): List<Reminder> = rows.values.toList()

    override suspend fun upsert(reminder: Reminder): Long {
        val id = if (reminder.id == 0L) nextId++ else reminder.id
        rows[id] = reminder.copy(id = id)
        return id
    }

    override suspend fun deleteForEvent(eventId: Long) {
        callLog += "deleteRows($eventId)"
        rows.values.filter { it.eventId == eventId }.forEach { rows.remove(it.id) }
    }

    override suspend fun delete(id: Long) { rows.remove(id) }
}

/** Settings held in memory; the editor only reads them to seed a new event's defaults. */
internal class FakeSettingsRepository(private var current: AppSettings = AppSettings()) : SettingsRepository {
    override val settings: Flow<AppSettings> get() = flowOf(current)
    override suspend fun current(): AppSettings = current
    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        current = transform(current)
    }
}
