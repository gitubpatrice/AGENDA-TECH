package com.filestech.agenda_tech.ui.screens.editor

import androidx.lifecycle.SavedStateHandle
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander
import com.filestech.agenda_tech.domain.usecase.DeleteEventUseCase
import com.filestech.agenda_tech.domain.usecase.FakeCalendarRepository
import com.filestech.agenda_tech.domain.usecase.FakeEventRepository
import com.filestech.agenda_tech.domain.usecase.FakeReminderRepository
import com.filestech.agenda_tech.domain.usecase.FakeSettingsRepository
import com.filestech.agenda_tech.domain.usecase.UpsertEventUseCase
import com.filestech.agenda_tech.system.AgendaChangeNotifier
import com.filestech.agenda_tech.system.alarm.ReminderScheduler
import com.filestech.agenda_tech.ui.navigation.Routes
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * What the editor's tests share: the repositories, the mocked scheduler and a way to open the editor on
 * an event. Two classes use it — [EventEditorViewModelTest] for saving, deleting and duplicating, and
 * [EventEditorTimeZoneTest] for the zone an event is read and written in.
 */
internal abstract class EventEditorTestBase {

    protected val zone: ZoneId = ZoneId.systemDefault()
    protected fun at(y: Int, m: Int, d: Int, h: Int): Long =
        LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    protected val eventRepo = FakeEventRepository()
    protected val calendarRepo = FakeCalendarRepository()
    protected val reminderRepo = FakeReminderRepository()
    protected val settingsRepo = FakeSettingsRepository()

    /**
     * Mocked, not faked: [ReminderScheduler] is a final class wired to AlarmManager, so there is no
     * seam to implement. What matters here is *which calls it receives, and in what order* — exactly
     * what a mock verifies.
     */
    protected val scheduler: ReminderScheduler = mockk(relaxed = true)

    /** Audit AG-2 — la couture qui rafraichit le widget apres chaque ecriture. */
    protected val agendaChanged: AgendaChangeNotifier = mockk(relaxed = true)

    protected val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUpEditor() {
        // viewModelScope pins Dispatchers.Main, which does not exist off-device.
        Dispatchers.setMain(dispatcher)
        calendarRepo.stored += Calendar(id = 1, name = "Perso", isDefault = true)
    }

    @AfterEach
    fun tearDownEditor() = Dispatchers.resetMain()

    protected fun viewModel(
        eventId: Long? = null,
        occurrenceStart: Long? = null,
    ): EventEditorViewModel {
        val args = buildMap<String, Any> {
            eventId?.let { put(Routes.ARG_EVENT_ID, it) }
            occurrenceStart?.let { put(Routes.ARG_OCCURRENCE_START, it) }
        }
        return EventEditorViewModel(
            upsertEvent = UpsertEventUseCase(eventRepo),
            deleteEvent = DeleteEventUseCase(eventRepo),
            eventRepository = eventRepo,
            calendarRepository = calendarRepo,
            reminderRepository = reminderRepo,
            reminderScheduler = scheduler,
            agendaChanged = agendaChanged,
            settingsRepository = settingsRepo,
            expander = RecurrenceExpander(),
            savedStateHandle = SavedStateHandle(args),
        )
    }

    protected fun seedEvent(
        id: Long = 10,
        title: String = "Dentiste",
        recurrence: RecurrenceRule? = null,
        sourceUid: String? = null,
    ) = Event(
        id = id,
        calendarId = 1,
        title = title,
        startUtcMillis = at(2026, 7, 20, 9),
        endUtcMillis = at(2026, 7, 20, 10),
        timeZoneId = zone.id,
        recurrence = recurrence,
        sourceUid = sourceUid,
    ).also { eventRepo.rows[id] = it }
}
