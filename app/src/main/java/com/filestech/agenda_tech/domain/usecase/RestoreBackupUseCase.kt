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
        val problem = validate(calendars, events)
        if (problem != null) {
            Outcome.Failure(AppError.Validation(problem))
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

    /**
     * Checks the file is internally consistent, returning the first problem or null.
     *
     * Runs before any write, and refuses the file **whole** rather than dropping the bad rows:
     * quietly shedding half an agenda would look exactly like a successful restore, which is the one
     * thing a backup must never do.
     *
     * None of this is reachable by a file the app itself wrote — it guards against a corrupted,
     * truncated or hand-edited one.
     */
    private fun validate(calendars: List<Calendar>, events: List<Event>): String? {
        // Room treats id 0 on an autoGenerate primary key as "unset" and silently assigns a fresh
        // rowid — every calendarId/parentId pointing at 0 would then dangle, with no error raised.
        calendars.firstOrNull { it.id <= 0 }?.let { return "calendar has a non-positive id (${it.id})" }
        events.firstOrNull { it.id <= 0 }?.let { return "event has a non-positive id (${it.id})" }

        // Ids are reinserted verbatim, so a duplicate would abort the transaction mid-way. Caught
        // here it is a clean refusal instead of a rollback we have to explain.
        val calendarIds = calendars.mapTo(HashSet()) { it.id }
        if (calendarIds.size != calendars.size) return "duplicate calendar id"
        val eventIds = events.mapTo(HashSet()) { it.id }
        if (eventIds.size != events.size) return "duplicate event id"

        // Would violate the events → calendars foreign key.
        events.firstOrNull { it.calendarId !in calendarIds }
            ?.let { return "event ${it.id} references unknown calendar ${it.calendarId}" }

        // No foreign key backs recurrence_parent_id, so nothing downstream would complain: a parent
        // id that happens to match an unrelated recurring event would silently hide one of *its*
        // occurrences (the expander groups overrides by this id alone).
        events.firstOrNull { it.recurrenceParentId != null && it.recurrenceParentId !in eventIds }
            ?.let { return "event ${it.id} overrides unknown parent ${it.recurrenceParentId}" }

        // The two halves of the RECURRENCE-ID model are meaningless apart: an override that names no
        // replaced instant would leave its master still producing the occurrence, showing both — and
        // one that names an instant but no parent replaces nothing at all.
        events.firstOrNull { (it.recurrenceParentId == null) != (it.originalStartUtcMillis == null) }
            ?.let { return "event ${it.id} is half an override (parent and original start must agree)" }

        return null
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
