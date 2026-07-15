package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.core.crypto.BackupEnvelope
import com.filestech.agenda_tech.core.crypto.wipe
import com.filestech.agenda_tech.core.result.AppError
import com.filestech.agenda_tech.core.result.Outcome
import com.filestech.agenda_tech.core.result.flatMap
import com.filestech.agenda_tech.domain.backup.BackupCodec
import com.filestech.agenda_tech.domain.backup.BackupCodec.toDomain
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.repository.BackupRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Replaces the agenda with the contents of an `.atbak` file.
 *
 * Decoding is fully completed **before** anything is written: a file that turns out to be truncated
 * or tampered must leave the existing agenda untouched, not half-overwritten.
 *
 * The caller's [password] is wiped, even on failure.
 */
@Singleton
class RestoreBackupUseCase @Inject constructor(
    private val envelope: BackupEnvelope,
    private val backupRepository: BackupRepository,
) {

    data class Result(val calendars: Int, val events: Int, val reminders: Int)

    /**
     * True when [file] carries the `.atbak` magic bytes. Decided before any key is derived, so it
     * lets the UI reject a wrong pick ("that is not a backup") without asking for a password, and
     * without that answer revealing anything about the password.
     */
    fun isRecognised(file: ByteArray): Boolean = envelope.isRecognised(file)

    suspend operator fun invoke(password: CharArray, file: ByteArray): Outcome<Result> =
        envelope.open(password, file)
            .flatMap { plaintext ->
                try {
                    decode(plaintext)
                } finally {
                    plaintext.wipe()
                }
            }
            .flatMap { decoded -> write(decoded) }

    private fun decode(plaintext: ByteArray): Outcome<Decoded> = try {
        val payload = BackupCodec.decodeFromJson(plaintext.toString(Charsets.UTF_8))
        val calendars = payload.calendars.map { it.toDomain() }
        val events = payload.events.map { it.toDomain() }
        val knownCalendarIds = calendars.mapTo(HashSet()) { it.id }
        // An event whose calendar is missing would violate the FK and abort the restore. A file this
        // inconsistent is not one we wrote, so refuse it outright rather than quietly shed events.
        val orphan = events.firstOrNull { it.calendarId !in knownCalendarIds }
        if (orphan != null) {
            Outcome.Failure(AppError.Validation("event ${orphan.id} references unknown calendar ${orphan.calendarId}"))
        } else {
            Outcome.Success(
                Decoded(
                    calendars = calendars,
                    events = events,
                    remindersByEventId = payload.events
                        .filter { it.reminderMinutes.isNotEmpty() }
                        .associate { it.id to it.reminderMinutes },
                ),
            )
        }
    } catch (t: Throwable) {
        // Covers malformed JSON, an unknown format version, and domain invariants rejected in
        // Event's init (e.g. end before start) — all "this file is not usable", none of them a crash.
        Outcome.Failure(AppError.Validation("backup file is not readable"))
    }

    private suspend fun write(decoded: Decoded): Outcome<Result> = try {
        backupRepository.replaceAll(decoded.calendars, decoded.events, decoded.remindersByEventId)
        Outcome.Success(
            Result(
                calendars = decoded.calendars.size,
                events = decoded.events.size,
                reminders = decoded.remindersByEventId.values.sumOf { it.size },
            ),
        )
    } catch (t: Throwable) {
        Outcome.Failure(AppError.Database(t))
    }

    private data class Decoded(
        val calendars: List<Calendar>,
        val events: List<Event>,
        val remindersByEventId: Map<Long, List<Int>>,
    )
}
