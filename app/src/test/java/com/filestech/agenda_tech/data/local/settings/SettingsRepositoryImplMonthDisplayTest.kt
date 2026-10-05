package com.filestech.agenda_tech.data.local.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.filestech.agenda_tech.domain.settings.MonthDisplay
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The month display through a real DataStore file: a setting read but never written — or written
 * under one key and read under another — passes every test that uses a fake repository.
 */
class SettingsRepositoryImplMonthDisplayTest {

    @TempDir
    lateinit var dir: File

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @AfterEach
    fun tearDown() = scope.cancel()

    private fun repository() = SettingsRepositoryImpl(
        PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") },
    )

    @Test
    fun `a fresh install reads the dots`() = runBlocking {
        assertThat(repository().current().monthDisplay).isEqualTo(MonthDisplay.DOTS)
    }

    @Test
    fun `a chosen display reads back`() = runBlocking {
        val repository = repository()

        repository.update { it.copy(monthDisplay = MonthDisplay.ROWS) }

        assertThat(repository.current().monthDisplay).isEqualTo(MonthDisplay.ROWS)
    }

    @Test
    fun `changing another setting keeps the display`() = runBlocking {
        val repository = repository()
        repository.update { it.copy(monthDisplay = MonthDisplay.TITLES) }

        repository.update { it.copy(showWeekNumbers = true) }

        assertThat(repository.current().monthDisplay).isEqualTo(MonthDisplay.TITLES)
    }
}
