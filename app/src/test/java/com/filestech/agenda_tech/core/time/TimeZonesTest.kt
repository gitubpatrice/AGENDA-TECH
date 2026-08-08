package com.filestech.agenda_tech.core.time

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId

/**
 * Audit F3 — the resolver every ingestion path now shares.
 *
 * Half of these tests exercise behaviour; the other half walk the transcribed CLDR table itself,
 * because a 140-row table copied by hand fails silently: a typo on the IANA side yields a name tzdb
 * rejects, and the resolver would quietly fall back instead of reporting anything.
 */
class TimeZonesTest {

    private val paris = ZoneId.of("Europe/Paris")

    // --- The table itself ----------------------------------------------------

    @Test
    fun `every windows zone name maps to a zone tzdb actually knows`() {
        val unknown = TimeZones.WINDOWS_TO_IANA.filterValues { iana ->
            runCatching { ZoneId.of(iana) }.isFailure
        }
        assertThat(unknown).isEmpty()
    }

    @Test
    fun `every windows zone name is reachable through the resolver`() {
        val unresolved = TimeZones.WINDOWS_TO_IANA.keys.filter { TimeZones.resolveOrNull(it) == null }
        assertThat(unresolved).isEmpty()
    }

    @Test
    fun `every key is lower-cased, or the case-insensitive lookup silently misses it`() {
        val notLowerCase = TimeZones.WINDOWS_TO_IANA.keys.filter { it != it.lowercase() }
        assertThat(notLowerCase).isEmpty()
    }

    @Test
    fun `a key tzdb also knows must denote the same zone as its mapping`() {
        // "UTC" is both a Windows name and a tzdb id. tzdb wins by lookup order, so the two answers
        // have to agree — otherwise the table's entry is unreachable AND wrong, and nothing says so.
        val contradictions = TimeZones.WINDOWS_TO_IANA.filter { (windows, iana) ->
            val direct = runCatching { ZoneId.of(windows) }.getOrNull()
            direct != null && direct.rules != ZoneId.of(iana).rules
        }
        assertThat(contradictions).isEmpty()
    }

    // --- Resolution ----------------------------------------------------------

    @Test
    fun `an outlook zone name resolves to the zone it denotes`() {
        // The whole point of the table: without it this is the device's zone, which is right only for
        // a user who happens to be in France, and wrong by a whole offset for one who is not.
        assertThat(TimeZones.resolve("Romance Standard Time", ZoneId.of("UTC"))).isEqualTo(paris)
        assertThat(TimeZones.resolve("W. Europe Standard Time", ZoneId.of("UTC")))
            .isEqualTo(ZoneId.of("Europe/Berlin"))
        assertThat(TimeZones.resolve("Tokyo Standard Time", ZoneId.of("UTC")))
            .isEqualTo(ZoneId.of("Asia/Tokyo"))
    }

    @Test
    fun `a windows name is matched whatever its case and quoting`() {
        assertThat(TimeZones.resolve("romance standard time", ZoneId.of("UTC"))).isEqualTo(paris)
        assertThat(TimeZones.resolve("ROMANCE STANDARD TIME", ZoneId.of("UTC"))).isEqualTo(paris)
        // RFC 5545 §3.2 allows a quoted parameter value, and producers quote inconsistently.
        assertThat(TimeZones.resolve("\"Romance Standard Time\"", ZoneId.of("UTC"))).isEqualTo(paris)
        assertThat(TimeZones.resolve("  Europe/Paris  ", ZoneId.of("UTC"))).isEqualTo(paris)
    }

    @Test
    fun `an IANA id passes through untouched`() {
        assertThat(TimeZones.normalize("Europe/Paris", ZoneId.of("UTC"))).isEqualTo("Europe/Paris")
        assertThat(TimeZones.normalize("UTC", paris)).isEqualTo("UTC")
        // A fixed offset is a legal TZID and a legal ZoneId; it loses DST, but the instant it yields
        // is the one the file meant, which a fallback would not be.
        assertThat(TimeZones.normalize("+02:00", paris)).isEqualTo("+02:00")
    }

    @Test
    fun `a legacy three-letter id resolves rather than falling back`() {
        // Emitted by old producers. ZoneId.of() alone rejects these; SHORT_IDS is the tier that has
        // them, and it is why resolveOrNull has three tiers and not two.
        assertThat(TimeZones.resolveOrNull("EST")).isNotNull()
        assertThat(TimeZones.resolve("ECT", ZoneId.of("UTC"))).isEqualTo(paris)
    }

    @Test
    fun `nothing recognisable yields the caller's fallback, not a guess`() {
        assertThat(TimeZones.resolveOrNull("Totally Made Up Time")).isNull()
        assertThat(TimeZones.resolveOrNull(null)).isNull()
        assertThat(TimeZones.resolveOrNull("")).isNull()
        assertThat(TimeZones.resolveOrNull("   ")).isNull()
        assertThat(TimeZones.resolve("Totally Made Up Time", paris)).isEqualTo(paris)
        assertThat(TimeZones.normalize(null, paris)).isEqualTo("Europe/Paris")
    }

    @Test
    fun `an injected zone name cannot survive normalisation`() {
        // The value that reaches the .ics exporter's TZID parameter. A CRLF here used to end the
        // content line; after normalisation the string is a ZoneId id, which cannot contain one.
        val hostile = "Europe/Paris\r\nDESCRIPTION:injecté"
        assertThat(TimeZones.resolveOrNull(hostile)).isNull()
        assertThat(TimeZones.normalize(hostile, paris)).isEqualTo("Europe/Paris")
    }

    // --- isCanonical, which decides what the v6 migration touches ------------

    @Test
    fun `isCanonical is true only for a value already usable as stored`() {
        assertThat(TimeZones.isCanonical("Europe/Paris")).isTrue()
        assertThat(TimeZones.isCanonical("UTC")).isTrue()
        // Resolvable, but not as stored — the migration must rewrite these, or every later reader
        // keeps re-resolving a string that only this table understands.
        assertThat(TimeZones.isCanonical("Romance Standard Time")).isFalse()
        assertThat(TimeZones.isCanonical("EST")).isFalse()
        assertThat(TimeZones.isCanonical("\"Europe/Paris\"")).isFalse()
        assertThat(TimeZones.isCanonical("Totally Made Up Time")).isFalse()
        assertThat(TimeZones.isCanonical(null)).isFalse()
    }
}
