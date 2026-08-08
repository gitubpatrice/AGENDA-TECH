package com.filestech.agenda_tech.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.filestech.agenda_tech.core.prefs.OneShotFlag
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    @Provides
    @Singleton
    fun settingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            // Audit DATA-3 — a corrupted prefs file resets to defaults instead of crashing every read.
            //
            // Audit S15 — and "defaults" is not a neutral word here. This same file carries
            // `lock_enabled` and the wrapped PIN, so replacing it switches the **app lock off**:
            // `LockRepositoryImpl.lockEnabled` falls back to `false`, `MainActivity` unlocks straight
            // through, and `AgendaWidget` stops hiding event titles on the home screen. A power cut
            // during a write is enough to trigger it, and so is one byte flipped by anyone with a
            // moment of file access — cheaper than attacking PBKDF2 or the Keystore.
            //
            // The handler stays: without it every read throws and the app is unusable, which is worse.
            // What changes is that the reset stops being silent. We cannot restore the lock — the PIN
            // wrap died with the file, and locking the user out of their own agenda with nothing to
            // type would be the one outcome worse than this one — so the honest response is to say so,
            // exactly as `DatabaseFactory` already does when it has to wipe the database.
            corruptionHandler = ReplaceFileCorruptionHandler {
                OneShotFlag.SETTINGS_RESET.raise(context)
                emptyPreferences()
            },
        ) { context.preferencesDataStoreFile("agenda_settings") }
}
