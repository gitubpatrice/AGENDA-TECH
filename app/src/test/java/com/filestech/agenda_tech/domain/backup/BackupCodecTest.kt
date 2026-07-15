package com.filestech.agenda_tech.domain.backup

import com.filestech.agenda_tech.domain.backup.BackupCodec.toDomain
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.model.Weekday
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The backup is only ever opened once the agenda is already lost, so these tests exist to catch a
 * field that stops round-tripping — the failure mode that is invisible until it is unfixable.
 */
class BackupCodecTest {

    private val calendars = listOf(
        Calendar(id = 1, name = "Perso", color = CalendarColor.GRAPE, isVisible = true, isDefault = true),
        Calendar(id = 2, name = "Travail", color = CalendarColor.BASIL, isVisible = false, sourceId = "device:6"),
    )

    /** Every optional field populated: a default-valued fixture would hide a dropped field. */
    private val fullEvent = Event(
        id = 10,
        calendarId = 1,
        title = "Dentiste",
        description = "Apporter la carte vitale",
        location = "Cabinet du Dr Martin",
        address = "12 rue des Lilas",
        postalCode = "84000",
        city = "Avignon",
        gpsCoordinates = "43.9493, 4.8055",
        startUtcMillis = 1_800_000_000_000,
        endUtcMillis = 1_800_003_600_000,
        timeZoneId = "Europe/Paris",
        allDay = false,
        recurrence = RecurrenceRule(
            freq = RecurrenceFreq.WEEKLY,
            interval = 2,
            byWeekdays = setOf(Weekday.MONDAY, Weekday.THURSDAY),
            count = 12,
            exDatesUtcMillis = listOf(1_800_600_000_000, 1_801_200_000_000),
        ),
        colorOverride = CalendarColor.TOMATO,
        sourceUid = "uid-abc-123",
    )

    private val overrideEvent = Event(
        id = 11,
        calendarId = 2,
        title = "Réunion déplacée",
        startUtcMillis = 1_800_100_000_000,
        endUtcMillis = 1_800_103_600_000,
        timeZoneId = "Europe/Paris",
        recurrenceParentId = 10,
        originalStartUtcMillis = 1_800_086_400_000,
    )

    @Test
    fun `round-trips every field of a fully populated event`() {
        val payload = BackupCodec.toPayload(
            calendars = calendars,
            events = listOf(fullEvent),
            remindersByEventId = mapOf(10L to listOf(10, 1440)),
            exportedAtUtcMillis = 1_800_000_000_000,
        )

        val restored = BackupCodec.decodeFromJson(BackupCodec.encodeToJson(payload))

        assertThat(restored).isEqualTo(payload)
        assertThat(restored.events.single().toDomain()).isEqualTo(fullEvent)
        assertThat(restored.calendars.map { it.toDomain() }).isEqualTo(calendars)
        assertThat(restored.events.single().reminderMinutes).containsExactly(10, 1440).inOrder()
    }

    @Test
    fun `preserves the per-occurrence override link`() {
        val payload = BackupCodec.toPayload(calendars, listOf(overrideEvent), emptyMap(), 0)

        val restored = BackupCodec.decodeFromJson(BackupCodec.encodeToJson(payload)).events.single().toDomain()

        assertThat(restored).isEqualTo(overrideEvent)
        assertThat(restored.isOverride).isTrue()
        assertThat(restored.recurrenceParentId).isEqualTo(10)
        assertThat(restored.originalStartUtcMillis).isEqualTo(1_800_086_400_000)
    }

    @Test
    fun `keeps sourceId and sourceUid so a re-import stays idempotent after a restore`() {
        val payload = BackupCodec.toPayload(calendars, listOf(fullEvent), emptyMap(), 0)

        val restored = BackupCodec.decodeFromJson(BackupCodec.encodeToJson(payload))

        assertThat(restored.calendars.first { it.id == 2L }.toDomain().sourceId).isEqualTo("device:6")
        assertThat(restored.events.single().toDomain().sourceUid).isEqualTo("uid-abc-123")
    }

    @Test
    fun `an unknown field written by a newer version does not break the restore`() {
        val json = BackupCodec.encodeToJson(BackupCodec.toPayload(calendars, listOf(overrideEvent), emptyMap(), 0))
        val withFutureField = json.replaceFirst("{", """{"someFieldFromTheFuture":42,""")

        val restored = BackupCodec.decodeFromJson(withFutureField)

        assertThat(restored.events).hasSize(1)
    }

    @Test
    fun `refuses a format version it cannot understand rather than guessing`() {
        val json = BackupCodec.encodeToJson(BackupCodec.toPayload(calendars, emptyList(), emptyMap(), 0))
        val fromTheFuture = json.replaceFirst(
            """"formatVersion":${BackupPayload.FORMAT_VERSION}""",
            """"formatVersion":${BackupPayload.FORMAT_VERSION + 1}""",
        )

        assertThrows<IllegalArgumentException> { BackupCodec.decodeFromJson(fromTheFuture) }
    }

    @Test
    fun `refuses malformed json`() {
        assertThrows<Exception> { BackupCodec.decodeFromJson("{ this is not json") }
    }
}
