package com.filestech.agenda_tech.data.local.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.filestech.agenda_tech.domain.holiday.HolidayCountry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The holiday country through a real DataStore file — see SettingsRepositoryImplMonthDisplayTest for why. */
class SettingsRepositoryImplHolidayCountryTest {

    @TempDir
    lateinit var dir: File

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @AfterEach
    fun tearDown() = scope.cancel()

    private val store by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") }
    }

    private fun repository() = SettingsRepositoryImpl(store)

    @Test
    fun `a fresh install shows no holidays`() = runBlocking {
        assertThat(repository().current().holidayCountry).isNull()
    }

    @Test
    fun `a chosen country reads back, and choosing none clears it`() = runBlocking {
        val repository = repository()

        repository.update { it.copy(holidayCountry = HolidayCountry.BE) }
        assertThat(repository.current().holidayCountry).isEqualTo(HolidayCountry.BE)

        repository.update { it.copy(holidayCountry = null) }
        assertThat(repository.current().holidayCountry).isNull()
    }

    @Test
    fun `it is stored by its ISO code, so adding a country never moves another`() = runBlocking {
        repository().update { it.copy(holidayCountry = HolidayCountry.US) }

        assertThat(store.data.first()[stringPreferencesKey("holiday_country")]).isEqualTo("US")
    }

    @Test
    fun `a code no country answers to reads as none`() = runBlocking {
        store.edit { it[stringPreferencesKey("holiday_country")] = "XX" }

        assertThat(repository().current().holidayCountry).isNull()
    }
}
