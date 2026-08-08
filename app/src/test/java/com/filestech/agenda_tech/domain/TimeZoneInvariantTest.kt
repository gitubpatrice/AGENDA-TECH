package com.filestech.agenda_tech.domain

import com.filestech.agenda_tech.core.time.TimeZones
import com.filestech.agenda_tech.domain.backup.BackupCodec.toDomain
import com.filestech.agenda_tech.domain.backup.BackupEvent
import com.filestech.agenda_tech.domain.device.DeviceEventMapper
import com.filestech.agenda_tech.domain.ics.IcsCodec
import com.filestech.agenda_tech.domain.model.DeviceEvent
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId

/**
 * One invariant, asserted where it is created rather than assumed: **every path that writes an event
 * writes a time zone that resolves back to itself.**
 *
 * ## Why this file exists (audit D6)
 *
 * The v6 migration repairs the rows already stored; keeping them repaired is the ingestion paths' job.
 * Today all four of them do it — verified by reading them — but that verification was a human one, and
 * `EntityMappers.toDomain` deliberately does **not** re-normalise on read (it would cost a `ZoneId.of`
 * per row on the hot path). So the invariant rests entirely on the writers, and nothing said so.
 *
 * If it ever breaks, the symptom is audit F3 again and it is silent, because the readers disagree
 * about the fallback: `RecurrenceExpander` drops to UTC, `IcsCodec.zoneOf` drops to UTC,
 * `BackupCodec` drops to the device zone. Three answers to one bad value.
 *
 * These are deliberately hostile inputs — a Windows name, a quoted name, an injected one, garbage —
 * because a test using `Europe/Paris` everywhere is exactly the test that missed F3 for two audits.
 */
class TimeZoneInvariantTest {

    private val paris = ZoneId.of("Europe/Paris")

    private val hostileZones = listOf(
        // Outlook: resolvable, but not as stored.
        "Romance Standard Time",
        // RFC 5545 allows the quotes; the resolved id does not carry them.
        "\"Europe/Paris\"",
        // Padded by a sloppy producer.
        "  Europe/Paris  ",
        // Legacy three-letter id.
        "EST",
        // Nothing resolves it.
        "Totally Made Up Time",
        // An injected value, the shape a hand-made .atbak can carry.
        "Europe/Paris\r\nX-EVIL:1",
        // Present but empty.
        "",
    )

    @Test
    fun `the ics importer writes a zone that resolves back to itself`() {
        hostileZones.forEach { zone ->
            val file = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n" +
                "SUMMARY:RDV\r\n" +
                "DTSTART;TZID=$zone:20250602T140000\r\n" +
                "END:VEVENT\r\nEND:VCALENDAR\r\n"
            val decoded = IcsCodec.decode(file, paris).singleOrNull() ?: return@forEach
            assertThat(TimeZones.isCanonical(decoded.timeZoneId)).isTrue()
        }
    }

    @Test
    fun `the device-calendar importer writes a zone that resolves back to itself`() {
        hostileZones.forEach { zone ->
            val event = DeviceEventMapper.toEvent(
                device = DeviceEvent(
                    uid = "u",
                    title = "RDV",
                    description = null,
                    location = null,
                    dtStartUtcMillis = 1_760_000_000_000L,
                    dtEndUtcMillis = 1_760_003_600_000L,
                    durationRfc = null,
                    allDay = false,
                    eventTimeZone = zone,
                    rrule = null,
                    exDate = null,
                    deviceId = 1,
                    originalId = null,
                    originalInstanceTime = null,
                ),
                calendarId = 1,
                defaultZone = paris,
            ) ?: return@forEach
            assertThat(TimeZones.isCanonical(event.timeZoneId)).isTrue()
        }
    }

    @Test
    fun `the backup restore writes a zone that resolves back to itself`() {
        hostileZones.forEach { zone ->
            val restored = BackupEvent(
                id = 1,
                calendarId = 1,
                title = "RDV",
                startUtcMillis = 1_760_000_000_000L,
                endUtcMillis = 1_760_003_600_000L,
                timeZoneId = zone,
            ).toDomain()
            assertThat(TimeZones.isCanonical(restored.timeZoneId)).isTrue()
        }
    }

    @Test
    fun `an all-day ics event is anchored to a zone that resolves back to itself`() {
        // The all-day branch takes a different route through parseVEvent — it uses the default zone
        // outright rather than the file's TZID — so it needs its own case, or half the branch is
        // untested and the invariant only half asserted.
        val file = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n" +
            "SUMMARY:Congé\r\n" +
            "DTSTART;VALUE=DATE:20250602\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"
        val decoded = IcsCodec.decode(file, paris).single()
        assertThat(decoded.allDay).isTrue()
        assertThat(TimeZones.isCanonical(decoded.timeZoneId)).isTrue()
    }
}
