package com.filestech.agenda_tech.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.domain.search.EventSearchHit
import com.filestech.agenda_tech.domain.usecase.SearchEventsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    searchEvents: SearchEventsUseCase,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * Hits for the current query.
     *
     * Deliberately **not** debounced: the corpus is folded once when the data changes, so typing only
     * runs a `contains` over pre-folded strings — there is no expensive work to throttle. Debouncing
     * would only buy a window in which the query says "dentiste" while the results still say nothing,
     * i.e. a flash of "no results" on every keystroke.
     */
    val hits: StateFlow<List<EventSearchHit>> = searchEvents(_query)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun clearQuery() {
        _query.value = ""
    }

    private companion object {
        /** Survives a configuration change without restarting the corpus flow. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
