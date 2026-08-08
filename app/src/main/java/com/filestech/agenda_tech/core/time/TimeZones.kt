package com.filestech.agenda_tech.core.time

import java.time.ZoneId

/**
 * The single place that turns an untrusted time-zone name into a usable [ZoneId].
 *
 * ## Why this exists
 *
 * Audit F3 — five call sites resolved a zone id, each with its own answer: the `.ics` importer stored
 * the raw `TZID` string but computed the instant with the *device* zone when that string was unknown,
 * the `.ics` exporter fell back to UTC, the expander fell back to UTC with a log, the device-calendar
 * importer fell back to the device zone, and the backup restore did not look at the field at all.
 * A zone name that only one of them understood therefore meant a different instant depending on which
 * one read it — the "asymmetric twin" defect, with five twins.
 *
 * ## What it accepts, in order
 *
 * 1. An IANA id (`Europe/Paris`), a fixed offset (`+02:00`) or any other name tzdb knows.
 * 2. A **Windows** zone name (`Romance Standard Time`). Outlook, Exchange and every `.ics` they export
 *    name zones this way; without the table a French meeting exported from Outlook is read in the
 *    device's zone, which is right only by luck and wrong by a whole offset for anyone travelling.
 * 3. A legacy three-letter id (`EST`, `ECT`) via [ZoneId.SHORT_IDS], still emitted by old producers.
 *
 * Anything else yields `null` from [resolveOrNull], and the caller decides what its fallback means —
 * deliberately not decided here, because the honest fallback differs: the device zone when reading a
 * file the user just picked, UTC when re-reading a row already stored.
 *
 * The name is trimmed and unquoted first: RFC 5545 allows `TZID="Europe/Paris"` and several producers
 * always quote.
 */
object TimeZones {

    /** The zone [rawId] names, or null when nothing recognises it. */
    fun resolveOrNull(rawId: String?): ZoneId? {
        val candidate = rawId?.trim()?.removeSurrounding("\"")?.trim().orEmpty()
        if (candidate.isEmpty()) return null
        tzdb(candidate)?.let { return it }
        WINDOWS_TO_IANA[candidate.lowercase()]?.let { iana -> tzdb(iana)?.let { return it } }
        // A short id resolves through a different overload, so it cannot be folded into tzdb() above.
        return runCatching { ZoneId.of(candidate, ZoneId.SHORT_IDS) }.getOrNull()
    }

    /** The zone [rawId] names, or [fallback] when nothing recognises it. */
    fun resolve(rawId: String?, fallback: ZoneId): ZoneId = resolveOrNull(rawId) ?: fallback

    /**
     * The canonical id to **store** for [rawId]. Storing the resolved id rather than the raw string is
     * what stops the file's spelling from outliving the import: every later reader then gets the same
     * zone this resolution used, instead of resolving the raw string again and possibly differently.
     */
    fun normalize(rawId: String?, fallback: ZoneId): String = resolve(rawId, fallback).id

    /** True when [rawId] is already a canonical, directly usable zone id — nothing to repair. */
    fun isCanonical(rawId: String?): Boolean = rawId != null && tzdb(rawId)?.id == rawId

    private fun tzdb(id: String): ZoneId? = runCatching { ZoneId.of(id) }.getOrNull()

    /**
     * CLDR `windowsZones.xml`, territory `001` (the default IANA zone for each Windows zone), plus the
     * two retired Windows names still found in archived `.ics` files. Keys are lower-cased for a
     * case-insensitive lookup.
     *
     * `internal` so `TimeZonesTest` can walk it and assert every value is a zone tzdb actually knows,
     * and every key is lower-cased. That is the half of a 140-row transcribed table a human reviewer
     * cannot check by eye, and a table nothing walks is a table that rots.
     */
    internal val WINDOWS_TO_IANA: Map<String, String> = mapOf(
        "dateline standard time" to "Etc/GMT+12",
        "utc-11" to "Etc/GMT+11",
        "aleutian standard time" to "America/Adak",
        "hawaiian standard time" to "Pacific/Honolulu",
        "marquesas standard time" to "Pacific/Marquesas",
        "alaskan standard time" to "America/Anchorage",
        "utc-09" to "Etc/GMT+9",
        "pacific standard time (mexico)" to "America/Tijuana",
        "utc-08" to "Etc/GMT+8",
        "pacific standard time" to "America/Los_Angeles",
        "us mountain standard time" to "America/Phoenix",
        "mountain standard time (mexico)" to "America/Chihuahua",
        "mountain standard time" to "America/Denver",
        "yukon standard time" to "America/Whitehorse",
        "central america standard time" to "America/Guatemala",
        "central standard time" to "America/Chicago",
        "easter island standard time" to "Pacific/Easter",
        "central standard time (mexico)" to "America/Mexico_City",
        "canada central standard time" to "America/Regina",
        "sa pacific standard time" to "America/Bogota",
        "eastern standard time (mexico)" to "America/Cancun",
        "eastern standard time" to "America/New_York",
        "haiti standard time" to "America/Port-au-Prince",
        "cuba standard time" to "America/Havana",
        "us eastern standard time" to "America/Indianapolis",
        "turks and caicos standard time" to "America/Grand_Turk",
        "paraguay standard time" to "America/Asuncion",
        "atlantic standard time" to "America/Halifax",
        "venezuela standard time" to "America/Caracas",
        "central brazilian standard time" to "America/Cuiaba",
        "sa western standard time" to "America/La_Paz",
        "pacific sa standard time" to "America/Santiago",
        "newfoundland standard time" to "America/St_Johns",
        "tocantins standard time" to "America/Araguaina",
        "e. south america standard time" to "America/Sao_Paulo",
        "sa eastern standard time" to "America/Cayenne",
        "argentina standard time" to "America/Buenos_Aires",
        "greenland standard time" to "America/Godthab",
        "montevideo standard time" to "America/Montevideo",
        "magallanes standard time" to "America/Punta_Arenas",
        "saint pierre standard time" to "America/Miquelon",
        "bahia standard time" to "America/Bahia",
        "utc-02" to "Etc/GMT+2",
        "mid-atlantic standard time" to "Atlantic/South_Georgia",
        "azores standard time" to "Atlantic/Azores",
        "cape verde standard time" to "Atlantic/Cape_Verde",
        "utc" to "Etc/UTC",
        "gmt standard time" to "Europe/London",
        "greenwich standard time" to "Atlantic/Reykjavik",
        "sao tome standard time" to "Africa/Sao_Tome",
        "morocco standard time" to "Africa/Casablanca",
        "w. europe standard time" to "Europe/Berlin",
        "central europe standard time" to "Europe/Budapest",
        "romance standard time" to "Europe/Paris",
        "central european standard time" to "Europe/Warsaw",
        "w. central africa standard time" to "Africa/Lagos",
        "gtb standard time" to "Europe/Bucharest",
        "middle east standard time" to "Asia/Beirut",
        "egypt standard time" to "Africa/Cairo",
        "e. europe standard time" to "Europe/Chisinau",
        "west bank standard time" to "Asia/Hebron",
        "south africa standard time" to "Africa/Johannesburg",
        "fle standard time" to "Europe/Kiev",
        "israel standard time" to "Asia/Jerusalem",
        "south sudan standard time" to "Africa/Juba",
        "kaliningrad standard time" to "Europe/Kaliningrad",
        "sudan standard time" to "Africa/Khartoum",
        "libya standard time" to "Africa/Tripoli",
        "namibia standard time" to "Africa/Windhoek",
        "jordan standard time" to "Asia/Amman",
        "arabic standard time" to "Asia/Baghdad",
        "turkey standard time" to "Europe/Istanbul",
        "arab standard time" to "Asia/Riyadh",
        "belarus standard time" to "Europe/Minsk",
        "russian standard time" to "Europe/Moscow",
        "e. africa standard time" to "Africa/Nairobi",
        "volgograd standard time" to "Europe/Volgograd",
        "iran standard time" to "Asia/Tehran",
        "arabian standard time" to "Asia/Dubai",
        "astrakhan standard time" to "Europe/Astrakhan",
        "azerbaijan standard time" to "Asia/Baku",
        "russia time zone 3" to "Europe/Samara",
        "mauritius standard time" to "Indian/Mauritius",
        "saratov standard time" to "Europe/Saratov",
        "georgian standard time" to "Asia/Tbilisi",
        "caucasus standard time" to "Asia/Yerevan",
        "afghanistan standard time" to "Asia/Kabul",
        "west asia standard time" to "Asia/Tashkent",
        "ekaterinburg standard time" to "Asia/Yekaterinburg",
        "pakistan standard time" to "Asia/Karachi",
        "qyzylorda standard time" to "Asia/Qyzylorda",
        "india standard time" to "Asia/Calcutta",
        "sri lanka standard time" to "Asia/Colombo",
        "nepal standard time" to "Asia/Katmandu",
        "central asia standard time" to "Asia/Almaty",
        "bangladesh standard time" to "Asia/Dhaka",
        "omsk standard time" to "Asia/Omsk",
        "myanmar standard time" to "Asia/Rangoon",
        "se asia standard time" to "Asia/Bangkok",
        "altai standard time" to "Asia/Barnaul",
        "w. mongolia standard time" to "Asia/Hovd",
        "north asia standard time" to "Asia/Krasnoyarsk",
        "n. central asia standard time" to "Asia/Novosibirsk",
        "tomsk standard time" to "Asia/Tomsk",
        "china standard time" to "Asia/Shanghai",
        "north asia east standard time" to "Asia/Irkutsk",
        "singapore standard time" to "Asia/Singapore",
        "w. australia standard time" to "Australia/Perth",
        "taipei standard time" to "Asia/Taipei",
        "ulaanbaatar standard time" to "Asia/Ulaanbaatar",
        "aus central w. standard time" to "Australia/Eucla",
        "transbaikal standard time" to "Asia/Chita",
        "tokyo standard time" to "Asia/Tokyo",
        "north korea standard time" to "Asia/Pyongyang",
        "korea standard time" to "Asia/Seoul",
        "yakutsk standard time" to "Asia/Yakutsk",
        "cen. australia standard time" to "Australia/Adelaide",
        "aus central standard time" to "Australia/Darwin",
        "e. australia standard time" to "Australia/Brisbane",
        "aus eastern standard time" to "Australia/Sydney",
        "west pacific standard time" to "Pacific/Port_Moresby",
        "tasmania standard time" to "Australia/Hobart",
        "vladivostok standard time" to "Asia/Vladivostok",
        "lord howe standard time" to "Australia/Lord_Howe",
        "bougainville standard time" to "Pacific/Bougainville",
        "russia time zone 10" to "Asia/Srednekolymsk",
        "magadan standard time" to "Asia/Magadan",
        "norfolk standard time" to "Pacific/Norfolk",
        "sakhalin standard time" to "Asia/Sakhalin",
        "central pacific standard time" to "Pacific/Guadalcanal",
        "russia time zone 11" to "Asia/Kamchatka",
        "kamchatka standard time" to "Asia/Kamchatka",
        "new zealand standard time" to "Pacific/Auckland",
        "utc+12" to "Etc/GMT-12",
        "fiji standard time" to "Pacific/Fiji",
        "chatham islands standard time" to "Pacific/Chatham",
        "utc+13" to "Etc/GMT-13",
        "tonga standard time" to "Pacific/Tongatapu",
        "samoa standard time" to "Pacific/Apia",
        "line islands standard time" to "Pacific/Kiritimati",
    )
}
