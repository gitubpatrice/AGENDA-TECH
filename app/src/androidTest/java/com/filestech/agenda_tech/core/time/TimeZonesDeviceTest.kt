package com.filestech.agenda_tech.core.time

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * The Windows → IANA table, checked against the time-zone database of a **real device**.
 *
 * `TimeZonesTest` already walks the table, but it runs on the JVM, against the JDK's own tzdb. That
 * proves nothing about Android: the app ships to API 26+, and several of the IANA ids CLDR uses are
 * "backward" links — `Asia/Calcutta`, `Asia/Katmandu`, `Asia/Rangoon`, `America/Godthab`,
 * `Europe/Kiev`. External review raised them as a possible silent failure: a device whose tzdb lacks
 * one would resolve nothing and fall back to the device zone, quietly reading an imported meeting in
 * the wrong zone.
 *
 * The legacy ids are kept on purpose rather than modernised. The modern spellings run the opposite
 * risk and a worse one: `Europe/Kyiv` only exists from tzdb 2022b, so it is **absent** on the older
 * Androids this app still supports, while `Europe/Kiev` is present on all of them. CLDR names them
 * the old way for the same reason.
 *
 * So the question is settled by measurement rather than by argument. Run on the Galaxy S9 (API 29) by
 * serial — the oldest device available, and therefore the one whose tzdb is most likely to be short.
 */
@RunWith(AndroidJUnit4::class)
class TimeZonesDeviceTest {

    @Test
    fun everyWindowsZoneNameResolvesOnThisDeviceTzdb() {
        val unresolved = TimeZones.WINDOWS_TO_IANA.filterValues { iana ->
            runCatching { ZoneId.of(iana) }.isFailure
        }
        assertThat(unresolved).isEmpty()
    }

    @Test
    fun anOutlookZoneNameResolvesToTheRightZoneOnThisDevice() {
        assertThat(TimeZones.resolve("Romance Standard Time", ZoneId.of("UTC")))
            .isEqualTo(ZoneId.of("Europe/Paris"))
        // The legacy-alias cases the review named, exercised by their Windows name so the whole
        // lookup chain is what is being measured, not just ZoneId.of.
        assertThat(TimeZones.resolveOrNull("India Standard Time")).isNotNull()
        assertThat(TimeZones.resolveOrNull("Nepal Standard Time")).isNotNull()
        assertThat(TimeZones.resolveOrNull("Myanmar Standard Time")).isNotNull()
        assertThat(TimeZones.resolveOrNull("Greenland Standard Time")).isNotNull()
        assertThat(TimeZones.resolveOrNull("FLE Standard Time")).isNotNull()
    }
}
