package com.filestech.agenda_tech.domain.device

import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.domain.model.DeviceEvent
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.Weekday
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

private const val CAL_ID = 7L

private fun deviceEvent(
    title: String? = "Réunion",
    start: Long = 1_700_000_000_000L,
    end: Long? = 1_700_003_600_000L,
    duration: String? = null,
    tz: String? = "Europe/Paris",
    allDay: Boolean = false,
    rrule: String? = null,
    exDate: String? = null,
    description: String? = null,
    location: String? = null,
    uid: String = "uid-1",
    deviceId: Long = 1L,
    originalId: Long? = null,
    originalInstanceTime: Long? = null,
) = DeviceEvent(
    uid = uid,
    title = title,
    description = description,
    location = location,
    dtStartUtcMillis = start,
    dtEndUtcMillis = end,
    durationRfc = duration,
    eventTimeZone = tz,
    allDay = allDay,
    rrule = rrule,
    exDate = exDate,
    deviceId = deviceId,
    originalId = originalId,
    originalInstanceTime = originalInstanceTime,
)

class DeviceEventMapperTest {

    @Test
    fun `maps a plain event`() {
        val e = DeviceEventMapper.toEvent(deviceEvent(), CAL_ID)!!
        assertThat(e.calendarId).isEqualTo(CAL_ID)
        assertThat(e.title).isEqualTo("Réunion")
        assertThat(e.startUtcMillis).isEqualTo(1_700_000_000_000L)
        assertThat(e.endUtcMillis).isEqualTo(1_700_003_600_000L)
        assertThat(e.timeZoneId).isEqualTo("Europe/Paris")
        assertThat(e.recurrence).isNull()
    }

    @Test
    fun `blank title is skipped`() {
        assertThat(DeviceEventMapper.toEvent(deviceEvent(title = "  "), CAL_ID)).isNull()
        assertThat(DeviceEventMapper.toEvent(deviceEvent(title = null), CAL_ID)).isNull()
    }

    @Test
    fun `strips bidi control characters from text`() {
        val rlo = 0x202E.toChar() // RIGHT-TO-LEFT OVERRIDE
        val pdi = 0x2069.toChar() // POP DIRECTIONAL ISOLATE
        val e = DeviceEventMapper.toEvent(
            deviceEvent(title = "a${rlo}b", description = "x${pdi}y"),
            CAL_ID,
        )!!
        assertThat(e.title).isEqualTo("ab")
        assertThat(e.description).isEqualTo("xy")
    }

    @Test
    fun `falls back to duration when end is null`() {
        val e = DeviceEventMapper.toEvent(deviceEvent(end = null, duration = "PT2H"), CAL_ID)!!
        assertThat(e.endUtcMillis - e.startUtcMillis).isEqualTo(2 * 60 * 60 * 1000L)
    }

    @Test
    fun `parses day-based duration`() {
        val e = DeviceEventMapper.toEvent(deviceEvent(end = null, duration = "P1D", allDay = true), CAL_ID)!!
        assertThat(e.endUtcMillis - e.startUtcMillis).isEqualTo(24 * 60 * 60 * 1000L)
    }

    @Test
    fun `defaults to one hour when neither end nor duration is known`() {
        val e = DeviceEventMapper.toEvent(deviceEvent(end = null, duration = null), CAL_ID)!!
        assertThat(e.endUtcMillis - e.startUtcMillis).isEqualTo(60 * 60 * 1000L)
    }

    @Test
    fun `parses a weekly RRULE with BYDAY and COUNT`() {
        val e = DeviceEventMapper.toEvent(
            deviceEvent(rrule = "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;COUNT=10"),
            CAL_ID,
        )!!
        val r = e.recurrence!!
        assertThat(r.freq).isEqualTo(RecurrenceFreq.WEEKLY)
        assertThat(r.interval).isEqualTo(2)
        assertThat(r.byWeekdays).containsExactly(Weekday.MONDAY, Weekday.WEDNESDAY)
        assertThat(r.count).isEqualTo(10)
        assertThat(r.untilUtcMillis).isNull()
    }

    @Test
    fun `parses UNTIL and tolerates a leading RRULE prefix`() {
        val e = DeviceEventMapper.toEvent(deviceEvent(rrule = "RRULE:FREQ=DAILY;UNTIL=20251231T235959Z"), CAL_ID)!!
        val expected = LocalDateTime.of(2025, 12, 31, 23, 59, 59).toInstant(ZoneOffset.UTC).toEpochMilli()
        val r = e.recurrence!!
        assertThat(r.freq).isEqualTo(RecurrenceFreq.DAILY)
        assertThat(r.untilUtcMillis).isEqualTo(expected)
    }

    @Test
    fun `parses EXDATE list with a TZID prefix`() {
        val e = DeviceEventMapper.toEvent(
            deviceEvent(rrule = "FREQ=DAILY", exDate = "TZID=UTC:20250101T090000Z,20250102T090000Z"),
            CAL_ID,
        )!!
        assertThat(e.recurrence!!.exDatesUtcMillis).hasSize(2)
    }

    @Test
    fun `unknown FREQ makes the event single`() {
        val e = DeviceEventMapper.toEvent(deviceEvent(rrule = "FREQ=HOURLY"), CAL_ID)!!
        assertThat(e.recurrence).isNull()
    }

    @Test
    fun `all-day event lands on its calendar date in the device zone, not shifted by the UTC offset`() {
        val paris = java.time.ZoneId.of("Europe/Paris")
        val utcMidnight = java.time.LocalDate.of(2026, 7, 14)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val e = DeviceEventMapper.toEvent(
            deviceEvent(start = utcMidnight, end = utcMidnight + 86_400_000L, allDay = true, tz = "UTC"),
            CAL_ID,
            paris,
        )!!
        val startDate = java.time.Instant.ofEpochMilli(e.startUtcMillis).atZone(paris).toLocalDate()
        assertThat(startDate).isEqualTo(java.time.LocalDate.of(2026, 7, 14))
        assertThat(e.timeZoneId).isEqualTo("Europe/Paris")
    }

    @Test
    fun `carries the source uid for idempotent re-import`() {
        val e = DeviceEventMapper.toEvent(deviceEvent(uid = "abc-123"), CAL_ID)!!
        assertThat(e.sourceUid).isEqualTo("abc-123")
    }

    @Test
    fun `caps oversized text fields`() {
        val e = DeviceEventMapper.toEvent(deviceEvent(title = "x".repeat(10_000)), CAL_ID)!!
        assertThat(e.title.length).isAtMost(4_000)
    }

    @Test
    fun `bounds a pathological duration instead of overflowing`() {
        val e = DeviceEventMapper.toEvent(deviceEvent(end = null, duration = "P999999999D"), CAL_ID)!!
        val span = e.endUtcMillis - e.startUtcMillis
        assertThat(span).isGreaterThan(0L)
        assertThat(span).isAtMost(3_650L * 24 * 60 * 60 * 1000)
    }

    @Test
    fun `nearestColor matches an exact palette colour and defaults on null`() {
        assertThat(DeviceEventMapper.nearestColor(CalendarColor.TOMATO.argb)).isEqualTo(CalendarColor.TOMATO)
        assertThat(DeviceEventMapper.nearestColor(null)).isEqualTo(CalendarColor.DEFAULT)
    }
}
