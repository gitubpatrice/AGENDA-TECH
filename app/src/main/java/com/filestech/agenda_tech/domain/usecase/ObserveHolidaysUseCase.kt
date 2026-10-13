package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.domain.holiday.Holiday
import com.filestech.agenda_tech.domain.holiday.Holidays
import com.filestech.agenda_tech.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject

/**
 * The public holidays a view shows, beside the occurrences ObserveOccurrencesInRangeUseCase gives it: those
 * of the country picked in the settings, from [from] to [until] excluded, by date — none when no country
 * is picked. Every view asks here, so a holiday is the same everywhere it appears.
 */
class ObserveHolidaysUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    operator fun invoke(from: LocalDate, until: LocalDate): Flow<Map<LocalDate, List<Holiday>>> =
        settingsRepository.settings
            .map { it.holidayCountry }
            .distinctUntilChanged()
            .map { country -> country?.let { Holidays.between(it, from, until).groupBy(Holiday::date) }.orEmpty() }
}
