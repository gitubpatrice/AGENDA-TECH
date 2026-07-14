package com.filestech.agenda_tech.ui.screens.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.core.result.Outcome
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.model.Reminder
import com.filestech.agenda_tech.domain.model.Weekday
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.repository.EventRepository
import com.filestech.agenda_tech.domain.repository.ReminderRepository
import com.filestech.agenda_tech.domain.repository.SettingsRepository
import com.filestech.agenda_tech.domain.usecase.DeleteEventUseCase
import com.filestech.agenda_tech.domain.usecase.UpsertEventUseCase
import com.filestech.agenda_tech.system.alarm.ReminderScheduler
import com.filestech.agenda_tech.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class EventEditorViewModel @Inject constructor(
    private val upsertEvent: UpsertEventUseCase,
    private val deleteEvent: DeleteEventUseCase,
    private val eventRepository: EventRepository,
    private val calendarRepository: CalendarRepository,
    private val reminderRepository: ReminderRepository,
    private val reminderScheduler: ReminderScheduler,
    private val settingsRepository: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val eventId: Long = savedStateHandle.get<Long>(Routes.ARG_EVENT_ID) ?: NEW
    private val initialDateEpochDay: Long = savedStateHandle.get<Long>(Routes.ARG_DATE) ?: NO_DATE

    /** The start of the specific occurrence tapped in a view (-1 when N/A); drives the scope prompt. */
    private val occurrenceStart: Long = savedStateHandle.get<Long>(Routes.ARG_OCCURRENCE_START) ?: NO_OCCURRENCE

    // Populated when editing an existing event so save/delete can preserve override links or
    // decide whether a scope prompt (this occurrence / whole series) is needed.
    private var loadedRecurrence: RecurrenceRule? = null
    private var loadedParentId: Long? = null
    private var loadedOriginalStart: Long? = null

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<EventEditorUiState> = _state.asStateFlow()

    /** True when the user tapped a single occurrence of a recurring master (needs a scope choice). */
    private fun isMasterOccurrence(): Boolean = loadedRecurrence != null && occurrenceStart > 0L

    init {
        viewModelScope.launch {
            val calendars = calendarRepository.observeAll().first()
            _state.update { current ->
                current.copy(
                    calendars = calendars,
                    selectedCalendarId = current.selectedCalendarId
                        .takeIf { id -> calendars.any { it.id == id } }
                        ?: defaultCalendarId(calendars),
                )
            }
            if (eventId > 0L) {
                loadEvent(eventId)
            } else {
                applyDefaults()
            }
        }
    }

    /** For a new event, seed duration / reminder / colour from the user's default settings. */
    private suspend fun applyDefaults() {
        val settings = settingsRepository.current()
        _state.update { current ->
            current.copy(
                endDateTime = current.startDateTime.plusMinutes(settings.defaultDurationMinutes.toLong()),
                colorOverride = settings.defaultEventColor,
                reminderMinutes = if (settings.defaultReminderMinutes >= 0) {
                    listOf(settings.defaultReminderMinutes)
                } else {
                    emptyList()
                },
            )
        }
    }

    private fun initialState(): EventEditorUiState {
        val date = if (initialDateEpochDay >= 0) LocalDate.ofEpochDay(initialDateEpochDay) else LocalDate.now(zone)
        val start = date.atTime(DEFAULT_HOUR, 0)
        return EventEditorUiState(
            isEditing = eventId > 0L,
            startDateTime = start,
            endDateTime = start.plusHours(1),
            recurrenceUntilDate = date.plusMonths(1),
        )
    }

    private suspend fun loadEvent(id: Long) {
        val event = eventRepository.getById(id) ?: return
        loadedRecurrence = event.recurrence
        loadedParentId = event.recurrenceParentId
        loadedOriginalStart = event.originalStartUtcMillis

        // For a tapped occurrence of a recurring master, show that occurrence's times (not the base).
        val editingOccurrence = event.recurrence != null && occurrenceStart > 0L
        val durationMillis = event.endUtcMillis - event.startUtcMillis
        val startMillis = if (editingOccurrence) occurrenceStart else event.startUtcMillis
        val endMillis = if (editingOccurrence) occurrenceStart + durationMillis else event.endUtcMillis

        val start = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDateTime()
        val storedEnd = Instant.ofEpochMilli(endMillis).atZone(zone).toLocalDateTime()
        // All-day end is stored as the exclusive next-midnight boundary — show the inclusive last day.
        val displayEnd = if (event.allDay) storedEnd.minusDays(1) else storedEnd
        val reminderMinutes = reminderRepository.getForEvent(id).map { it.minutesBefore }.sorted()
        val rule = event.recurrence
        val defaultUntil = start.toLocalDate().plusMonths(1)
        val recurrenceEnd = when {
            rule?.count != null -> RecurrenceEnd.AFTER_COUNT
            rule?.untilUtcMillis != null -> RecurrenceEnd.ON_DATE
            else -> RecurrenceEnd.NEVER
        }
        val untilDate = rule?.untilUtcMillis
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            ?: defaultUntil
        _state.update {
            it.copy(
                title = event.title,
                allDay = event.allDay,
                startDateTime = start,
                endDateTime = displayEnd,
                selectedCalendarId = event.calendarId,
                colorOverride = event.colorOverride,
                recurrenceFreq = rule?.freq,
                recurrenceInterval = rule?.interval ?: 1,
                recurrenceByWeekdays = rule?.byWeekdays ?: emptySet(),
                recurrenceEnd = recurrenceEnd,
                recurrenceCount = rule?.count ?: it.recurrenceCount,
                recurrenceUntilDate = untilDate,
                reminderMinutes = reminderMinutes,
                description = event.description.orEmpty(),
                location = event.location.orEmpty(),
                deleteNeedsScope = editingOccurrence,
            )
        }
    }

    fun onAddReminder(minutesBefore: Int) = _state.update {
        if (minutesBefore in it.reminderMinutes) it
        else it.copy(reminderMinutes = (it.reminderMinutes + minutesBefore).sorted())
    }

    fun onRemoveReminder(minutesBefore: Int) = _state.update {
        it.copy(reminderMinutes = it.reminderMinutes - minutesBefore)
    }

    fun onTitleChange(value: String) = _state.update {
        it.copy(title = value, error = if (it.error == EditorError.BLANK_TITLE) null else it.error)
    }

    fun onAllDayChange(allDay: Boolean) = _state.update { it.copy(allDay = allDay) }

    fun onStartDateChange(date: LocalDate) = _state.update {
        it.withStart(date.atTime(it.startDateTime.toLocalTime()))
    }

    fun onStartTimeChange(hour: Int, minute: Int) = _state.update {
        it.withStart(it.startDateTime.toLocalDate().atTime(hour, minute))
    }

    fun onEndDateChange(date: LocalDate) = _state.update {
        it.copy(endDateTime = date.atTime(it.endDateTime.toLocalTime()), error = null)
    }

    fun onEndTimeChange(hour: Int, minute: Int) = _state.update {
        it.copy(endDateTime = it.endDateTime.toLocalDate().atTime(hour, minute), error = null)
    }

    fun onCalendarSelect(calendarId: Long) = _state.update { it.copy(selectedCalendarId = calendarId) }

    /** Per-event colour override; null inherits the calendar's colour. */
    fun onColorChange(color: CalendarColor?) = _state.update { it.copy(colorOverride = color) }

    fun onRecurrenceSelect(freq: RecurrenceFreq?) = _state.update { current ->
        // Seed weekly BYDAY with the start day's weekday so a fresh weekly rule has a sensible default.
        val seededWeekdays = if (freq == RecurrenceFreq.WEEKLY && current.recurrenceByWeekdays.isEmpty()) {
            setOf(Weekday.fromIso(current.startDateTime.dayOfWeek.value))
        } else {
            current.recurrenceByWeekdays
        }
        current.copy(recurrenceFreq = freq, recurrenceByWeekdays = seededWeekdays)
    }

    fun onRecurrenceIntervalChange(interval: Int) = _state.update {
        it.copy(recurrenceInterval = interval.coerceIn(1, MAX_INTERVAL))
    }

    fun onToggleRecurrenceWeekday(weekday: Weekday) = _state.update {
        val next = if (weekday in it.recurrenceByWeekdays) {
            it.recurrenceByWeekdays - weekday
        } else {
            it.recurrenceByWeekdays + weekday
        }
        it.copy(recurrenceByWeekdays = next)
    }

    fun onRecurrenceEndChange(end: RecurrenceEnd) = _state.update { it.copy(recurrenceEnd = end) }

    fun onRecurrenceCountChange(count: Int) = _state.update {
        it.copy(recurrenceCount = count.coerceIn(1, MAX_COUNT))
    }

    fun onRecurrenceUntilDateChange(date: LocalDate) = _state.update { it.copy(recurrenceUntilDate = date) }

    fun onDescriptionChange(value: String) = _state.update { it.copy(description = value) }

    fun onLocationChange(value: String) = _state.update { it.copy(location = value) }

    fun onSave() {
        val current = _state.value
        if (current.title.isBlank()) {
            _state.update { it.copy(error = EditorError.BLANK_TITLE) }
            return
        }
        val (startMillis, endMillis) = current.toInstants()
        if (endMillis < startMillis) {
            _state.update { it.copy(error = EditorError.END_BEFORE_START) }
            return
        }
        if (isMasterOccurrence()) {
            _state.update { it.copy(scopePrompt = ScopePrompt.SAVE) }
            return
        }
        persist(asOverride = false)
    }

    fun onDelete() {
        if (eventId <= 0L) return
        if (isMasterOccurrence()) {
            _state.update { it.copy(scopePrompt = ScopePrompt.DELETE) }
            return
        }
        val parentId = loadedParentId
        val originalStart = loadedOriginalStart
        if (parentId != null && originalStart != null) {
            deleteOverrideAndExclude(parentId, originalStart)
            return
        }
        deleteDirect()
    }

    /**
     * FIAB-4 — deleting a *modified* occurrence (an override) must also exclude its original date
     * from the master's EXDATE; otherwise removing the override lets the master's default occurrence
     * reappear on that day, so "delete" would silently behave as "revert".
     */
    private fun deleteOverrideAndExclude(parentId: Long, originalStart: Long) {
        viewModelScope.launch {
            reminderScheduler.cancelEvent(eventId)
            val master = eventRepository.getById(parentId)
            val rule = master?.recurrence
            if (master != null && rule != null && originalStart !in rule.exDatesUtcMillis) {
                val updated = master.copy(recurrence = rule.copy(exDatesUtcMillis = rule.exDatesUtcMillis + originalStart))
                // ROB-NEW-2 — exclude on the master and drop the override in one transaction.
                eventRepository.upsertAndDelete(updated, eventId)
                reminderScheduler.rescheduleEvent(parentId)
            } else {
                deleteEvent(eventId)
            }
            _state.update { it.copy(isDeleted = true) }
        }
    }

    /** Resolve the scope dialog: apply to this occurrence only, or the whole series. */
    fun confirmScope(applyToSeries: Boolean) {
        val prompt = _state.value.scopePrompt ?: return
        _state.update { it.copy(scopePrompt = null) }
        when (prompt) {
            ScopePrompt.SAVE -> persist(asOverride = !applyToSeries)
            ScopePrompt.DELETE -> if (applyToSeries) deleteSeries() else deleteThisOccurrence()
        }
    }

    fun dismissScope() = _state.update { it.copy(scopePrompt = null) }

    private fun persist(asOverride: Boolean) {
        val current = _state.value
        val (startMillis, endMillis) = current.toInstants()
        val event = Event(
            id = if (asOverride) 0L else if (current.isEditing) eventId else 0L,
            calendarId = current.selectedCalendarId,
            title = current.title,
            description = current.description.ifBlank { null },
            location = current.location.ifBlank { null },
            startUtcMillis = startMillis,
            endUtcMillis = endMillis,
            timeZoneId = zone.id,
            allDay = current.allDay,
            // An override is a single event; the whole-series save keeps the recurrence rule.
            recurrence = if (asOverride) null else current.toRecurrenceRule(),
            colorOverride = current.colorOverride,
            recurrenceParentId = if (asOverride) eventId else loadedParentId,
            originalStartUtcMillis = if (asOverride) occurrenceStart else loadedOriginalStart,
        )
        val reminderMinutes = current.reminderMinutes
        viewModelScope.launch {
            when (val result = upsertEvent(event)) {
                is Outcome.Success -> {
                    val savedId = result.value
                    // Audit SEC-1 — cancel armed alarms BEFORE replacing reminder rows (fresh ids
                    // otherwise leave the old PendingIntents armed → phantom notifications).
                    reminderScheduler.cancelEvent(savedId)
                    reminderRepository.deleteForEvent(savedId)
                    reminderMinutes.forEach { minutes ->
                        reminderRepository.upsert(Reminder(eventId = savedId, minutesBefore = minutes))
                    }
                    reminderScheduler.rescheduleEvent(savedId)
                    if (asOverride) {
                        // FIAB-NEW-3 — exclude the overridden date from the master (iCalendar
                        // RECURRENCE-ID model) so the master's default occurrence AND its reminder no
                        // longer fire at that instant next to the moved override.
                        excludeFromMaster(eventId, occurrenceStart)
                    }
                    _state.update { it.copy(isSaved = true) }
                }
                is Outcome.Failure -> _state.update { it.copy(error = EditorError.SAVE_FAILED) }
            }
        }
    }

    private suspend fun excludeFromMaster(masterId: Long, originalStart: Long) {
        val master = eventRepository.getById(masterId)
        val rule = master?.recurrence ?: return
        if (originalStart in rule.exDatesUtcMillis) return
        eventRepository.upsert(master.copy(recurrence = rule.copy(exDatesUtcMillis = rule.exDatesUtcMillis + originalStart)))
        reminderScheduler.rescheduleEvent(masterId)
    }

    private fun deleteDirect() {
        viewModelScope.launch {
            reminderScheduler.cancelEvent(eventId)
            deleteEvent(eventId)
            _state.update { it.copy(isDeleted = true) }
        }
    }

    private fun deleteSeries() {
        viewModelScope.launch {
            reminderScheduler.cancelEvent(eventId)
            eventRepository.deleteSeriesAtomic(eventId) // ROB-NEW-2 — master + overrides in one transaction
            _state.update { it.copy(isDeleted = true) }
        }
    }

    /** Exclude just the tapped occurrence: add its start to the master's EXDATE. */
    private fun deleteThisOccurrence() {
        viewModelScope.launch {
            val master = eventRepository.getById(eventId)
            val rule = master?.recurrence
            if (master == null || rule == null) {
                deleteDirect()
                return@launch
            }
            val updatedRule = rule.copy(exDatesUtcMillis = rule.exDatesUtcMillis + occurrenceStart)
            eventRepository.upsert(master.copy(recurrence = updatedRule))
            reminderScheduler.rescheduleEvent(eventId)
            _state.update { it.copy(isDeleted = true) }
        }
    }

    private fun EventEditorUiState.withStart(newStart: LocalDateTime): EventEditorUiState {
        val newEnd = if (endDateTime.isBefore(newStart)) newStart.plusHours(1) else endDateTime
        return copy(startDateTime = newStart, endDateTime = newEnd, error = null)
    }

    private fun EventEditorUiState.toInstants(): Pair<Long, Long> =
        if (allDay) {
            val start = startDateTime.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
            // Inclusive end date -> exclusive next-midnight boundary (a single all-day = 24h).
            val end = endDateTime.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            start to end
        } else {
            startDateTime.atZone(zone).toInstant().toEpochMilli() to
                endDateTime.atZone(zone).toInstant().toEpochMilli()
        }

    private fun defaultCalendarId(calendars: List<Calendar>): Long =
        (calendars.firstOrNull { it.isDefault } ?: calendars.firstOrNull())?.id ?: 0L

    private fun EventEditorUiState.toRecurrenceRule(): RecurrenceRule? {
        val freq = recurrenceFreq ?: return null
        val count = if (recurrenceEnd == RecurrenceEnd.AFTER_COUNT) recurrenceCount.coerceAtLeast(1) else null
        val until = if (recurrenceEnd == RecurrenceEnd.ON_DATE) {
            // Inclusive end date → last instant of that day, so an occurrence on it is kept.
            recurrenceUntilDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        } else {
            null
        }
        return RecurrenceRule(
            freq = freq,
            interval = recurrenceInterval.coerceAtLeast(1),
            byWeekdays = if (freq == RecurrenceFreq.WEEKLY) recurrenceByWeekdays else emptySet(),
            count = count,
            untilUtcMillis = until,
        )
    }

    private companion object {
        const val NEW = -1L
        const val NO_DATE = -1L
        const val NO_OCCURRENCE = -1L
        const val DEFAULT_HOUR = 9
        const val MAX_INTERVAL = 999
        const val MAX_COUNT = 999
    }
}
