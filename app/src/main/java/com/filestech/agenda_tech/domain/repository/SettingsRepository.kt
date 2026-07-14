package com.filestech.agenda_tech.domain.repository

import com.filestech.agenda_tech.domain.settings.AppSettings
import kotlinx.coroutines.flow.Flow

/** Reads and writes the user's [AppSettings]. Backed by DataStore. */
interface SettingsRepository {

    /** Streams the current settings, re-emitting on every change. */
    val settings: Flow<AppSettings>

    /** One-shot read of the current settings (for non-reactive call sites like the widget). */
    suspend fun current(): AppSettings

    /** Applies [transform] to the current settings and persists the result. */
    suspend fun update(transform: (AppSettings) -> AppSettings)
}
