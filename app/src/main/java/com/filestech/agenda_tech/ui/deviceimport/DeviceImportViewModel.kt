package com.filestech.agenda_tech.ui.deviceimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.domain.model.DeviceCalendar
import com.filestech.agenda_tech.domain.usecase.ImportDeviceEventsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Drives the "import from the device calendar" screen: permission → list → select → import. */
@HiltViewModel
class DeviceImportViewModel @Inject constructor(
    private val importDeviceEvents: ImportDeviceEventsUseCase,
) : ViewModel() {

    sealed interface UiState {
        /** Waiting for the user to grant READ_CALENDAR. */
        data object NeedPermission : UiState
        data object Loading : UiState
        data class Ready(val calendars: List<DeviceCalendar>) : UiState

        /** Permission granted but the device exposes no calendars. */
        data object Empty : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.NeedPermission)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _result = MutableStateFlow<ImportDeviceEventsUseCase.Result?>(null)
    val result: StateFlow<ImportDeviceEventsUseCase.Result?> = _result.asStateFlow()

    private val _cleared = MutableStateFlow(false)
    val cleared: StateFlow<Boolean> = _cleared.asStateFlow()

    fun onPermissionGranted() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            val calendars = importDeviceEvents.listCalendars()
            _state.value = if (calendars.isEmpty()) UiState.Empty else UiState.Ready(calendars)
            // Pre-select everything: the common case is "import all my calendars".
            _selected.value = calendars.map { it.id }.toSet()
        }
    }

    fun onPermissionDenied() {
        _state.value = UiState.NeedPermission
    }

    fun toggle(calendarId: Long) {
        _selected.value = _selected.value.toMutableSet().apply {
            if (!add(calendarId)) remove(calendarId)
        }
    }

    fun import() {
        if (_importing.value) return
        val ids = _selected.value.toList()
        if (ids.isEmpty()) return
        _importing.value = true
        viewModelScope.launch {
            _result.value = importDeviceEvents(ids)
            _importing.value = false
        }
    }

    fun consumeResult() {
        _result.value = null
    }

    /** Wipes previously imported calendars/events (fixes legacy duplicates) before a clean re-import. */
    fun clearImported() {
        if (_importing.value) return
        _importing.value = true
        viewModelScope.launch {
            importDeviceEvents.clearImported()
            _importing.value = false
            _cleared.value = true
        }
    }

    fun consumeCleared() {
        _cleared.value = false
    }
}
