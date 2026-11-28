package com.filestech.agenda_tech.ui.screens.bridges

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.core.time.DeviceZone
import com.filestech.agenda_tech.domain.holiday.Bridge
import com.filestech.agenda_tech.domain.holiday.Bridges
import com.filestech.agenda_tech.domain.holiday.HolidayCountry
import com.filestech.agenda_tech.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

/**
 * What the bridge days screen shows. [loaded] stays false until the settings are read, so the screen
 * does not flash its "no country" text first. [country] null: no holidays chosen, hence no bridges.
 */
data class BridgesUiState(
    val loaded: Boolean = false,
    val country: HolidayCountry? = null,
    val years: List<Int> = emptyList(),
    val year: Int = 0,
    val bridges: List<Bridge> = emptyList(),
)

/**
 * The bridges of the country chosen, for this year or the next — the two a leave is planned in: this
 * year's still ahead of [today] (one whose last day to take is today still counts), the next year's all.
 * The year opens on this one, or on the next when none is left in it, rather than on an empty list.
 */
internal fun bridgesState(country: HolidayCountry?, chosenYear: Int?, today: LocalDate): BridgesUiState {
    if (country == null) return BridgesUiState(loaded = true)
    val years = listOf(today.year, today.year + 1)
    val ahead = { year: Int -> Bridges.inYear(country, year).filter { !it.daysToTake.last().isBefore(today) } }
    val thisYear = ahead(today.year)
    val year = chosenYear?.takeIf { it in years } ?: if (thisYear.isEmpty()) today.year + 1 else today.year
    val bridges = if (year == today.year) thisYear else ahead(year)
    return BridgesUiState(loaded = true, country = country, years = years, year = year, bridges = bridges)
}

@HiltViewModel
class BridgesViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    deviceZone: DeviceZone,
) : ViewModel() {

    private val chosenYear = MutableStateFlow<Int?>(null)

    // Today in the phone's zone, read again when it changes (a journey), as the calendar views do.
    val uiState: StateFlow<BridgesUiState> = combine(
        settingsRepository.settings.map { it.holidayCountry }.distinctUntilChanged(),
        chosenYear,
        deviceZone.zone,
    ) { country, year, zone -> bridgesState(country, year, LocalDate.now(zone)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BridgesUiState())

    fun selectYear(year: Int) {
        chosenYear.value = year
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
