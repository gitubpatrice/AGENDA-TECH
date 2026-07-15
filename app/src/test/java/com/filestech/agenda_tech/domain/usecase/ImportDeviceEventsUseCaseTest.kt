package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.domain.device.DeviceEventMapper
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.DeviceCalendar
import com.filestech.agenda_tech.domain.model.DeviceEvent
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.repository.DeviceCalendarRepository
import com.filestech.agenda_tech.domain.repository.EventRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private const val DEVICE_CAL_ID = 6L
private const val FALLBACK_NAME = "Calendrier importé"

private fun deviceEvent(
    uid: String,
    deviceId: Long,
    title: String = "RDV",
    start: Long = 1_700_000_000_000L,
) = DeviceEvent(
    uid = uid,
    title = title,
    description = null,
    location = null,
    dtStartUtcMillis = start,
    dtEndUtcMillis = start + 3_600_000L,
    durationRfc = null,
    eventTimeZone = "Europe/Paris",
    allDay = false,
    rrule = null,
    exDate = null,
    deviceId = deviceId,
    originalId = null,
    originalInstanceTime = null,
)

/** In-memory device calendar source — the whole point of the domain interface (no Android needed). */
private class FakeDeviceCalendars(
    private val calendars: List<DeviceCalendar> = listOf(
        DeviceCalendar(id = DEVICE_CAL_ID, displayName = "Google", accountName = "a@b.c", colorArgb = null),
    ),
    var events: List<DeviceEvent> = emptyList(),
) : DeviceCalendarRepository {
    override suspend fun listCalendars(): List<DeviceCalendar> = calendars
    override suspend fun readEvents(deviceCalendarId: Long): List<DeviceEvent> = events
}

private class FakeCalendarRepository : CalendarRepository {
    val stored = mutableListOf<Calendar>()
    private var nextId = 1L

    override fun observeAll(): Flow<List<Calendar>> = flowOf(stored)
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
    // Mirrors the DAO: only rows that carry a source_id, never a hand-made calendar.
    override suspend fun deleteImported() { stored.removeAll { !it.isDefault && it.sourceId != null } }
}

private class FakeEventRepository : EventRepository {
    /** Rows keyed by id, mimicking the DB's insert-or-update semantics. */
    val rows = mutableMapOf<Long, Event>()
    private var nextId = 100L

    override fun observeForExpansion(windowStartUtcMillis: Long, windowEndUtcMillis: Long): Flow<List<Event>> =
        flowOf(rows.values.toList())
    override fun observeByCalendar(calendarId: Long): Flow<List<Event>> =
        flowOf(rows.values.filter { it.calendarId == calendarId })
    override fun observeOverrides(): Flow<List<Event>> = flowOf(emptyList())
    override suspend fun getById(id: Long): Event? = rows[id]
    override suspend fun getAll(): List<Event> = rows.values.toList()
    override suspend fun deleteOverridesForParent(parentId: Long) = Unit
    override suspend fun deleteSeriesAtomic(parentId: Long) = Unit
    override suspend fun upsertAndDelete(event: Event, deleteId: Long) = Unit
    override suspend fun upsert(event: Event): Long {
        val id = if (event.id == 0L) nextId++ else event.id
        rows[id] = event.copy(id = id)
        return id
    }
    override suspend fun upsertAll(events: List<Event>) {
        events.forEach { upsert(it) }
    }
    override suspend fun sourceUidMap(calendarId: Long): Map<String, Long> =
        rows.values
            .filter { it.calendarId == calendarId && it.sourceUid != null }
            .associate { it.sourceUid!! to it.id }
    override suspend fun delete(id: Long) { rows.remove(id) }
}

class ImportDeviceEventsUseCaseTest {

    private val devices = FakeDeviceCalendars()
    private val calendars = FakeCalendarRepository()
    private val events = FakeEventRepository()
    private val useCase = ImportDeviceEventsUseCase(
        deviceCalendars = devices,
        calendarRepository = calendars,
        eventRepository = events,
    )

    /** The screen resolves the localised fallback name; tests pin a fixed one. */
    private suspend fun import(vararg deviceCalendarIds: Long) =
        useCase(deviceCalendarIds.toList(), FALLBACK_NAME)

    @Test
    fun `imports the events of the selected calendar into a new calendar`() = runTest {
        devices.events = listOf(deviceEvent(uid = "a", deviceId = 1), deviceEvent(uid = "b", deviceId = 2))

        val result = import(DEVICE_CAL_ID)

        assertThat(result.calendars).isEqualTo(1)
        assertThat(result.events).isEqualTo(2)
        assertThat(result.failedCalendars).isEqualTo(0)
        assertThat(events.rows).hasSize(2)
        assertThat(calendars.stored.single().sourceId).isEqualTo("device:$DEVICE_CAL_ID")
    }

    @Test
    fun `re-importing does not duplicate anything`() = runTest {
        devices.events = listOf(deviceEvent(uid = "a", deviceId = 1), deviceEvent(uid = "b", deviceId = 2))

        import(DEVICE_CAL_ID)
        import(DEVICE_CAL_ID)

        assertThat(events.rows).hasSize(2)
        assertThat(calendars.stored).hasSize(1) // the calendar is reused via its source_id
    }

    @Test
    fun `re-importing updates an event edited at the source, in place`() = runTest {
        devices.events = listOf(deviceEvent(uid = "a", deviceId = 1, title = "Avant"))
        import(DEVICE_CAL_ID)
        val idBefore = events.rows.keys.single()

        devices.events = listOf(deviceEvent(uid = "a", deviceId = 1, title = "Après"))
        import(DEVICE_CAL_ID)

        assertThat(events.rows).hasSize(1)
        assertThat(events.rows.keys.single()).isEqualTo(idBefore)
        assertThat(events.rows.values.single().title).isEqualTo("Après")
    }

    @Test
    fun `an event created before its account synced is not duplicated once it gets a sync id`() = runTest {
        // First import: no sync id yet, so the reader falls back to the local row-id form (audit U1).
        devices.events = listOf(deviceEvent(uid = DeviceEventMapper.rowIdUid(42), deviceId = 42))
        import(DEVICE_CAL_ID)
        val idBefore = events.rows.keys.single()

        // The account syncs: same row, now carrying a real sync id.
        devices.events = listOf(deviceEvent(uid = "server-sync-id", deviceId = 42))
        import(DEVICE_CAL_ID)

        assertThat(events.rows).hasSize(1)
        assertThat(events.rows.keys.single()).isEqualTo(idBefore)
        assertThat(events.rows.values.single().sourceUid).isEqualTo("server-sync-id")
    }

    @Test
    fun `a newly created event is added on re-import without touching the existing ones`() = runTest {
        devices.events = listOf(deviceEvent(uid = "a", deviceId = 1))
        import(DEVICE_CAL_ID)

        devices.events = listOf(deviceEvent(uid = "a", deviceId = 1), deviceEvent(uid = "new", deviceId = 2))
        val result = import(DEVICE_CAL_ID)

        assertThat(events.rows).hasSize(2)
        assertThat(result.events).isEqualTo(2)
    }

    @Test
    fun `a device calendar with no name falls back to the localised name`() = runTest {
        val nameless = FakeDeviceCalendars(
            calendars = listOf(
                DeviceCalendar(id = DEVICE_CAL_ID, displayName = "  ", accountName = "", colorArgb = null),
            ),
        ).apply { events = listOf(deviceEvent(uid = "a", deviceId = 1)) }
        val useCase = ImportDeviceEventsUseCase(nameless, calendars, events)

        useCase(listOf(DEVICE_CAL_ID), FALLBACK_NAME)

        assertThat(calendars.stored.single().name).isEqualTo(FALLBACK_NAME)
    }

    @Test
    fun `an unknown calendar is reported as failed, never silently ignored`() = runTest {
        val result = import(999L)

        assertThat(result.calendars).isEqualTo(0)
        assertThat(result.failedCalendars).isEqualTo(1)
        assertThat(events.rows).isEmpty()
    }

    @Test
    fun `events that cannot be mapped are skipped, the rest still import`() = runTest {
        devices.events = listOf(
            deviceEvent(uid = "ok", deviceId = 1),
            deviceEvent(uid = "blank", deviceId = 2, title = "   "), // blank title → unmappable
        )

        val result = import(DEVICE_CAL_ID)

        assertThat(result.events).isEqualTo(1)
        assertThat(events.rows.values.single().sourceUid).isEqualTo("ok")
    }

    @Test
    fun `clearImported wipes imported calendars but keeps the default one`() = runTest {
        calendars.upsert(Calendar(name = "Perso", isDefault = true))
        devices.events = listOf(deviceEvent(uid = "a", deviceId = 1))
        import(DEVICE_CAL_ID)
        assertThat(calendars.stored).hasSize(2)

        useCase.clearImported()

        assertThat(calendars.stored.single().name).isEqualTo("Perso")
    }

    @Test
    fun `clearImported never deletes a calendar the user created by hand`() = runTest {
        // The dialog promises "your own calendars are kept". A hand-made calendar is NOT the default
        // one, so wiping on is_default alone used to destroy it and its events (audit CRITICAL).
        calendars.upsert(Calendar(name = "Perso", isDefault = true))
        calendars.upsert(Calendar(name = "Travail", isDefault = false)) // hand-made: no sourceId
        devices.events = listOf(deviceEvent(uid = "a", deviceId = 1))
        import(DEVICE_CAL_ID)
        assertThat(calendars.stored).hasSize(3)

        useCase.clearImported()

        assertThat(calendars.stored.map { it.name }).containsExactly("Perso", "Travail")
        assertThat(calendars.stored.none { it.sourceId != null }).isTrue()
    }
}
