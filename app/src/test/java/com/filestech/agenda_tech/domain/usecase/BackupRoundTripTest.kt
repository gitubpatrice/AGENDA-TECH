package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.core.crypto.AeadCipher
import com.filestech.agenda_tech.core.crypto.BackupEnvelope
import com.filestech.agenda_tech.core.result.AppError
import com.filestech.agenda_tech.core.result.Outcome
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.model.Weekday
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Export → restore across the real use cases and the real crypto, with only storage faked.
 *
 * This is the test that matters: [BackupCodecTest] proves the fields survive JSON and
 * [com.filestech.agenda_tech.core.crypto.BackupEnvelopeTest] proves the file resists tampering — but
 * only this one proves an actual agenda comes back.
 */
class BackupRoundTripTest {

    private val envelope = BackupEnvelope(AeadCipher())
    private val calendarRepo = FakeCalendarRepository()
    private val eventRepo = FakeEventRepository()
    private val backupRepo = FakeBackupRepository()

    private val export = ExportBackupUseCase(calendarRepo, eventRepo, backupRepo, envelope)
    private val restore = RestoreBackupUseCase(envelope, backupRepo)

    private fun password() = "un mot de passe correct".toCharArray()

    private val perso = Calendar(id = 1, name = "Perso", color = CalendarColor.GRAPE, isDefault = true)
    private val travail = Calendar(id = 2, name = "Travail", color = CalendarColor.BASIL, sourceId = "device:6")

    private val weekly = Event(
        id = 10,
        calendarId = 1,
        title = "Sport",
        location = "Salle",
        address = "3 avenue du Stade",
        postalCode = "84000",
        city = "Avignon",
        gpsCoordinates = "43.9493, 4.8055",
        startUtcMillis = 1_800_000_000_000,
        endUtcMillis = 1_800_003_600_000,
        timeZoneId = "Europe/Paris",
        recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY, byWeekdays = setOf(Weekday.TUESDAY)),
    )
    private val meeting = Event(
        id = 11,
        calendarId = 2,
        title = "Point équipe",
        startUtcMillis = 1_800_100_000_000,
        endUtcMillis = 1_800_103_600_000,
        timeZoneId = "Europe/Paris",
        sourceUid = "uid-42",
    )

    /**
     * Seeds all three fakes with the *same* agenda. In the app they are one database — the export
     * reads through the calendar/event repositories and the restore writes through the backup one.
     * Seeding only the read side would leave the write side empty, and every "the existing agenda is
     * untouched" assertion below would then pass against nothing.
     */
    private fun seedAgenda() {
        calendarRepo.stored += listOf(perso, travail)
        listOf(weekly, meeting).forEach { eventRepo.rows[it.id] = it }
        backupRepo.calendars += listOf(perso, travail)
        backupRepo.events += listOf(weekly, meeting)
        backupRepo.reminders[10] = listOf(10, 1440)
        backupRepo.reminders[11] = listOf(30)
    }

    private suspend fun exportBytes(): ByteArray =
        (export(password(), nowUtcMillis = 1_800_000_000_000) as Outcome.Success).value.bytes

    @Test
    fun `an exported agenda comes back whole`() = runTest {
        seedAgenda()

        val file = exportBytes()
        // Wipe the agenda the way a lost phone would.
        backupRepo.calendars.clear(); backupRepo.events.clear(); backupRepo.reminders.clear()

        val result = restore(password(), file)

        assertThat(result).isInstanceOf(Outcome.Success::class.java)
        assertThat((result as Outcome.Success).value)
            .isEqualTo(RestoreBackupUseCase.Result(calendars = 2, events = 2, reminders = 3))
        assertThat(backupRepo.calendars).containsExactly(perso, travail)
        assertThat(backupRepo.events).containsExactly(weekly, meeting)
        assertThat(backupRepo.reminders).containsExactly(10L, listOf(10, 1440), 11L, listOf(30))
    }

    @Test
    fun `the export reports what it actually wrote`() = runTest {
        seedAgenda()

        val out = export(password(), nowUtcMillis = 0) as Outcome.Success

        assertThat(out.value.calendars).isEqualTo(2)
        assertThat(out.value.events).isEqualTo(2)
    }

    @Test
    fun `an empty agenda still produces a restorable file`() = runTest {
        val file = exportBytes()

        assertThat(restore(password(), file)).isInstanceOf(Outcome.Success::class.java)
        assertThat(backupRepo.events).isEmpty()
    }

    @Test
    fun `a short password is refused before anything is encrypted`() = runTest {
        seedAgenda()

        val out = export("court".toCharArray(), nowUtcMillis = 0)

        assertThat(out).isInstanceOf(Outcome.Failure::class.java)
        assertThat((out as Outcome.Failure).error).isInstanceOf(AppError.Validation::class.java)
    }

    @Test
    fun `a wrong password leaves the existing agenda untouched`() = runTest {
        seedAgenda()
        val file = exportBytes()
        val before = backupRepo.events.toList()

        val result = restore("un mot de passe faux xx".toCharArray(), file)

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat(backupRepo.events).isEqualTo(before)
    }

    @Test
    fun `a truncated file leaves the existing agenda untouched`() = runTest {
        seedAgenda()
        val file = exportBytes()
        val before = backupRepo.events.toList()

        // Decoding must fully complete before the wipe, or a half-written file costs the user both
        // their old agenda and their new one.
        val result = restore(password(), file.copyOfRange(0, file.size / 2))

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat(backupRepo.events).isEqualTo(before)
    }

    @Test
    fun `a storage failure mid-restore is reported, not swallowed`() = runTest {
        seedAgenda()
        val file = exportBytes()
        backupRepo.failOnWrite = true

        val result = restore(password(), file)

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat((result as Outcome.Failure).error).isInstanceOf(AppError.Database::class.java)
    }

    @Test
    fun `a file whose events name a missing calendar is refused outright`() = runTest {
        seedAgenda()
        val file = exportBytes()
        // Drop a calendar the way a hand-edited or half-merged file would.
        val tampered = reseal(file) { payload -> payload.copy(calendars = payload.calendars.filter { it.id == 1L }) }

        val result = restore(password(), tampered)

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat((result as Outcome.Failure).error).isInstanceOf(AppError.Validation::class.java)
        // Refused wholesale: quietly shedding the events would look like a successful restore.
        assertThat(backupRepo.calendars).hasSize(2)
    }

    @Test
    fun `a file violating a domain invariant is refused rather than crashing`() = runTest {
        seedAgenda()
        val file = exportBytes()
        val tampered = reseal(file) { payload ->
            // End before start — Event's init would throw if this reached the model unguarded.
            payload.copy(events = payload.events.map { it.copy(endUtcMillis = it.startUtcMillis - 1) })
        }

        val result = restore(password(), tampered)

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat((result as Outcome.Failure).error).isInstanceOf(AppError.Validation::class.java)
    }

    /** Decrypt, mutate the payload, re-encrypt — forges the file an attacker with the password could. */
    private fun reseal(
        file: ByteArray,
        mutate: (com.filestech.agenda_tech.domain.backup.BackupPayload) -> com.filestech.agenda_tech.domain.backup.BackupPayload,
    ): ByteArray {
        val plaintext = (envelope.open(password(), file) as Outcome.Success).value
        val payload = com.filestech.agenda_tech.domain.backup.BackupCodec
            .decodeFromJson(plaintext.toString(Charsets.UTF_8))
        val json = com.filestech.agenda_tech.domain.backup.BackupCodec.encodeToJson(mutate(payload))
        return (envelope.seal(password(), json.toByteArray(Charsets.UTF_8)) as Outcome.Success).value
    }
}
