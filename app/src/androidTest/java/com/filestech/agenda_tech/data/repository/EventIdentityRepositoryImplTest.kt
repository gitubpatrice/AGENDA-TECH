package com.filestech.agenda_tech.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.agenda_tech.data.local.db.AppDatabase
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.Event
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The identity an event receives when it first leaves the app (2026-10-22), on a real Room database —
 * what the fake only mirrors: the conditional UPDATE, the read-back, the transaction around an editor
 * save, and `updated_at`.
 *
 * Plain SQLite in memory, not SQLCipher: the question is the SQL, not the encryption. Each test is
 * `{ runBlocking { … } }` and returns Unit — JUnit 4 rejects a class whose test methods return a value.
 */
@RunWith(AndroidJUnit4::class)
class EventIdentityRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var events: EventRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        events = EventRepositoryImpl(db, db.eventDao(), Dispatchers.IO)
        runBlocking { db.calendarDao().upsert(Calendar(id = CALENDAR, name = "Perso", isDefault = true).toEntity(0)) }
    }

    @After
    fun tearDown() = db.close()

    private fun event(id: Long, title: String = "Dentiste", sourceUid: String? = null) = Event(
        id = id,
        calendarId = CALENDAR,
        title = title,
        startUtcMillis = START,
        endUtcMillis = START + HOUR,
        timeZoneId = "Europe/Paris",
        sourceUid = sourceUid,
    )

    private suspend fun stored(id: Long) = db.eventDao().getById(id)

    @Test
    fun anEventLeavingTheAppIsGivenAnIdentityOnceAndKeepsIt() {
        runBlocking {
            db.eventDao().upsert(event(10).toEntity(createdAt = 0, updatedAt = 0))

            val first = events.withIcsIdentities(listOf(event(10))).single().sourceUid
            // A caller holding the row as it was before: it must receive the identity already given.
            val second = events.withIcsIdentities(listOf(event(10))).single().sourceUid

            assertThat(first).matches("ics:[0-9a-f-]{36}@filestech")
            assertThat(second).isEqualTo(first)
            assertThat(stored(10)!!.sourceUid).isEqualTo(first)
        }
    }

    @Test
    fun anImportedEventKeepsTheUidItCameWith() {
        runBlocking {
            db.eventDao().upsert(event(10, sourceUid = "ics:abc@example.com").toEntity(createdAt = 0, updatedAt = 0))

            val back = events.withIcsIdentities(listOf(event(10, sourceUid = "ics:abc@example.com"))).single()

            assertThat(back.sourceUid).isEqualTo("ics:abc@example.com")
            assertThat(stored(10)!!.updatedAt).isEqualTo(0)
        }
    }

    @Test
    fun givingAnIdentityCountsAsAChangeForTheBackupPrompt() {
        runBlocking {
            db.eventDao().upsert(event(10).toEntity(createdAt = 0, updatedAt = 1))

            events.withIcsIdentities(listOf(event(10)))

            assertThat(stored(10)!!.updatedAt).isGreaterThan(1)
        }
    }

    @Test
    fun anEditorSaveHoldingTheStaleNullKeepsTheIdentity() {
        runBlocking {
            db.eventDao().upsert(event(10).toEntity(createdAt = 0, updatedAt = 0))
            val given = events.withIcsIdentities(listOf(event(10))).single().sourceUid

            // What the editor writes when it loaded the event before the share gave it a UID.
            events.upsert(event(10, title = "Dentiste reporté"))

            val row = stored(10)!!
            assertThat(row.title).isEqualTo("Dentiste reporté")
            assertThat(row.sourceUid).isEqualTo(given)
        }
    }

    private companion object {
        const val CALENDAR = 1L
        const val HOUR = 3_600_000L
        const val START = 1_790_000_000_000L
    }
}
