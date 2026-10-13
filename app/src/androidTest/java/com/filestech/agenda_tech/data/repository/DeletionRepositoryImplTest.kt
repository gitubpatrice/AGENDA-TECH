package com.filestech.agenda_tech.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.agenda_tech.data.local.db.AppDatabase
import com.filestech.agenda_tech.data.local.db.entity.ReminderEntity
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.DeletedEvents
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.model.SeriesExclusion
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The undo of a deletion, on a real Room database — what a fake cannot show: the foreign keys, the
 * transaction, and an insert that refuses to overwrite.
 *
 * Plain SQLite in memory, not SQLCipher: the question is the SQL, not the encryption, which
 * DatabaseEncryptionTest covers.
 *
 * Each test is `{ runBlocking { … } }` and returns Unit: JUnit 4 rejects a whole class whose test
 * methods return a value (`= runBlocking { assertThat(…) }` returns Truth's `Ordered`) — App Manager
 * Tech lost its instrumented tests to exactly that.
 */
@RunWith(AndroidJUnit4::class)
class DeletionRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: DeletionRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = DeletionRepositoryImpl(db, db.eventDao(), db.reminderDao(), Dispatchers.IO)
        runBlocking { db.calendarDao().upsert(Calendar(id = CALENDAR, name = "Perso", isDefault = true).toEntity(0)) }
    }

    @After
    fun tearDown() = db.close()

    private fun event(id: Long, title: String = "Dentiste", rule: RecurrenceRule? = null, parentId: Long? = null) = Event(
        id = id,
        calendarId = CALENDAR,
        title = title,
        startUtcMillis = START,
        endUtcMillis = START + HOUR,
        timeZoneId = "Europe/Paris",
        recurrence = rule,
        recurrenceParentId = parentId,
        originalStartUtcMillis = parentId?.let { START + WEEK },
    )

    private suspend fun stored(id: Long) = db.eventDao().getById(id)?.toDomain()

    @Test
    fun restoresTheEventUnderItsOwnIdWithItsReminders() {
        runBlocking {
            repository.restore(
                DeletedEvents(events = listOf(event(10)), reminderMinutesByEventId = mapOf(10L to listOf(15, 60))),
            )

            assertThat(stored(10)).isEqualTo(event(10))
            assertThat(db.reminderDao().getForEvent(10).map { it.minutesBefore }).containsExactly(15, 60)
        }
    }

    @Test
    fun restoresASeriesWithItsOverrideAndTheExclusionsTheMasterCarried() {
        runBlocking {
            val master = event(10, "Sport", RecurrenceRule(freq = RecurrenceFreq.WEEKLY).excluding(START + WEEK))
            val override = event(20, "Sport — déplacé", parentId = 10)

            repository.restore(DeletedEvents(events = listOf(master, override)))

            assertThat(stored(10)).isEqualTo(master)
            assertThat(stored(20)).isEqualTo(override)
        }
    }

    @Test
    fun liftsTheExclusionOnTheSeriesAsItIsNowNotAsItWas() {
        runBlocking {
            val excluded = START + WEEK
            // The series as the deletion left it — then renamed by the user before tapping "Undo".
            val renamed = event(10, "Sport — renommé", RecurrenceRule(freq = RecurrenceFreq.WEEKLY).excluding(excluded))
            db.eventDao().upsert(renamed.toEntity(0, 0))

            repository.restore(DeletedEvents(addedExclusion = SeriesExclusion(10, excluded)))

            val now = stored(10)!!
            assertThat(now.title).isEqualTo("Sport — renommé")
            assertThat(now.recurrence!!.exDatesUtcMillis).isEmpty()
        }
    }

    @Test
    fun anExclusionOnASeriesDeletedMeanwhileIsSimplyNothingToLift() {
        runBlocking {
            repository.restore(DeletedEvents(addedExclusion = SeriesExclusion(10, START + WEEK)))

            assertThat(stored(10)).isNull()
        }
    }

    @Test
    fun aCalendarDeletedMeanwhileRestoresNothingAtAll() {
        runBlocking {
            val orphan = event(11).copy(calendarId = 99)

            val failure = runCatching {
                repository.restore(
                    DeletedEvents(events = listOf(event(10), orphan), reminderMinutesByEventId = mapOf(10L to listOf(15))),
                )
            }

            assertThat(failure.isFailure).isTrue()
            // All or nothing: the first event, whose calendar exists, did not come back on its own.
            assertThat(stored(10)).isNull()
            assertThat(db.reminderDao().getAll()).isEmpty()
        }
    }

    @Test
    fun anIdAlreadyTakenIsRefusedNotOverwritten() {
        runBlocking {
            val occupant = event(10, "Autre chose")
            db.eventDao().upsert(occupant.toEntity(0, 0))
            db.reminderDao().upsert(ReminderEntity(eventId = 10, minutesBefore = 5))

            val failure = runCatching {
                repository.restore(DeletedEvents(events = listOf(event(10)), reminderMinutesByEventId = mapOf(10L to listOf(60))))
            }

            assertThat(failure.isFailure).isTrue()
            assertThat(stored(10)).isEqualTo(occupant)
            assertThat(db.reminderDao().getForEvent(10).map { it.minutesBefore }).containsExactly(5)
        }
    }

    private companion object {
        const val CALENDAR = 1L
        const val HOUR = 3_600_000L
        const val WEEK = 7 * 24 * HOUR
        const val START = 1_790_000_000_000L
    }
}
