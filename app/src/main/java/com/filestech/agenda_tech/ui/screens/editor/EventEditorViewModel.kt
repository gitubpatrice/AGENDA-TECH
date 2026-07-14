package com.filestech.agenda_tech.ui.screens.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.core.result.Outcome
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.repository.EventRepository
import com.filestech.agenda_tech.domain.usecase.DeleteEventUseCase
import com.filestech.agenda_tech.domain.usecase.UpsertEventUseCase
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
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val eventId: Long = savedStateHandle.get<Long>(Routes.ARG_EVENT_ID) ?: NEW
    private val initialDateEpochDay: Long = savedStateHandle.get<Long>(Routes.ARG_DATE) ?: NO_DATE

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<EventEditorUiState> = _state.asStateFlow()

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
            if (eventId > 0L) loadEvent(eventId)
        }
    }

    private fun initialState(): EventEditorUiState {
        val date = if (initialDateEpochDay >= 0) LocalDate.ofEpochDay(initialDateEpochDay) else LocalDate.now(zone)
        val start = date.atTime(DEFAULT_HOUR, 0)
        return EventEditorUiState(
            isEditing = eventId > 0L,
            startDateTime = start,
            endDateTime = start.plusHours(1),
        )
    }

    private suspend fun loadEvent(id: Long) {
        val event = eventRepository.getById(id) ?: return
        val start = Instant.ofEpochMilli(event.startUtcMillis).atZone(zone).toLocalDateTime()
        val storedEnd = Instant.ofEpochMilli(event.endUtcMillis).atZone(zone).toLocalDateTime()
        // All-day end is stored as the exclusive next-midnight boundary — show the inclusive last day.
        val displayEnd = if (event.allDay) storedEnd.minusDays(1) else storedEnd
        _state.update {
            it.copy(
                title = event.title,
                allDay = event.allDay,
                startDateTime = start,
                endDateTime = displayEnd,
                selectedCalendarId = event.calendarId,
                recurrenceFreq = event.recurrence?.freq,
                description = event.description.orEmpty(),
                location = event.location.orEmpty(),
            )
        }
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

    fun onRecurrenceSelect(freq: RecurrenceFreq?) = _state.update { it.copy(recurrenceFreq = freq) }

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
        val event = Event(
            id = if (current.isEditing) eventId else 0L,
            calendarId = current.selectedCalendarId,
            title = current.title,
            description = current.description.ifBlank { null },
            location = current.location.ifBlank { null },
            startUtcMillis = startMillis,
            endUtcMillis = endMillis,
            timeZoneId = zone.id,
            allDay = current.allDay,
            recurrence = current.recurrenceFreq?.let { RecurrenceRule(freq = it) },
        )
        viewModelScope.launch {
            when (upsertEvent(event)) {
                is Outcome.Success -> _state.update { it.copy(isSaved = true) }
                is Outcome.Failure -> _state.update { it.copy(error = EditorError.SAVE_FAILED) }
            }
        }
    }

    fun onDelete() {
        if (eventId <= 0L) return
        viewModelScope.launch {
            deleteEvent(eventId)
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

    private companion object {
        const val NEW = -1L
        const val NO_DATE = -1L
        const val DEFAULT_HOUR = 9
    }
}
