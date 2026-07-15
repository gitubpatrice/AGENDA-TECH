package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.core.crypto.BackupEnvelope
import com.filestech.agenda_tech.core.crypto.wipe
import com.filestech.agenda_tech.core.result.AppError
import com.filestech.agenda_tech.core.result.Outcome
import com.filestech.agenda_tech.core.result.map
import com.filestech.agenda_tech.domain.backup.BackupCodec
import com.filestech.agenda_tech.domain.repository.BackupRepository
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.repository.EventRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshots the whole agenda into an encrypted `.atbak` byte array.
 *
 * The caller's [password] is wiped, even on failure.
 */
@Singleton
class ExportBackupUseCase @Inject constructor(
    private val calendarRepository: CalendarRepository,
    private val eventRepository: EventRepository,
    private val backupRepository: BackupRepository,
    private val envelope: BackupEnvelope,
) {

    /** The sealed file plus what went into it, so the UI can tell the user what it just saved. */
    data class Export(val bytes: ByteArray, val calendars: Int, val events: Int)

    suspend operator fun invoke(password: CharArray, nowUtcMillis: Long): Outcome<Export> {
        if (password.size < BackupEnvelope.MIN_PASSWORD_LENGTH) {
            password.wipe()
            return Outcome.Failure(AppError.Validation("password too short"))
        }
        val payload = try {
            BackupCodec.toPayload(
                calendars = calendarRepository.observeAll().first(),
                events = eventRepository.getAll(),
                remindersByEventId = backupRepository.reminderMinutesByEventId(),
                exportedAtUtcMillis = nowUtcMillis,
            )
        } catch (t: Throwable) {
            password.wipe()
            return Outcome.Failure(AppError.Database(t))
        }

        val json = BackupCodec.encodeToJson(payload)
        // Read the JSON back and compare before sealing. A backup is only ever opened on the day the
        // agenda is already lost, so a codec regression that silently drops rows would be discovered
        // exactly when it can no longer be fixed. Costs microseconds and needs no key.
        val reread = try {
            BackupCodec.decodeFromJson(json)
        } catch (t: Throwable) {
            password.wipe()
            return Outcome.Failure(AppError.Crypto("backup failed self-check", t))
        }
        if (reread != payload) {
            password.wipe()
            return Outcome.Failure(AppError.Crypto("backup failed self-check"))
        }

        val plaintext = json.toByteArray(Charsets.UTF_8)
        return try {
            envelope.seal(password, plaintext).map { bytes ->
                Export(bytes = bytes, calendars = payload.calendars.size, events = payload.events.size)
            }
        } finally {
            plaintext.wipe()
        }
    }

    /** Suggested file name, e.g. `agenda-tech-2026-07-15.atbak`. */
    fun fileName(isoDate: String): String = "agenda-tech-$isoDate.$FILE_EXTENSION"

    companion object {
        const val FILE_EXTENSION = "atbak"
        const val MIME_TYPE = "application/octet-stream"
    }
}
