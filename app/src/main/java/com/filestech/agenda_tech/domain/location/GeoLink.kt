package com.filestech.agenda_tech.domain.location

import java.net.URLEncoder

/**
 * Builds an RFC 5870 `geo:` link from what the user typed, so the event's place can be opened in
 * whatever maps app the phone has. Pure — no Android — hence unit-testable.
 *
 * The app itself never touches the network nor the location provider: it only hands a link over to
 * another app, exactly like the `.ics` and About-page links.
 */
object GeoLink {

    /** Latitudes/longitudes outside these are not a place, they are a typo. */
    private val LAT_RANGE = -90.0..90.0
    private val LNG_RANGE = -180.0..180.0

    /** Separators people actually type between the two numbers. */
    private val SEPARATORS = charArrayOf(',', ';', '/')

    /**
     * `"44.0512, 5.0489"` → `geo:44.0512,5.0489?q=44.0512,5.0489(Label)`.
     *
     * Returns null when [raw] is blank or isn't a usable coordinate pair — the caller then hides the
     * "open" affordance rather than firing an intent that would go nowhere.
     */
    fun fromCoordinates(raw: String?, label: String? = null): String? {
        val (lat, lng) = parseCoordinates(raw) ?: return null
        val point = "$lat,$lng"
        // `q=` makes the maps app drop a labelled pin; without it some apps only centre the map.
        val query = label?.takeIf { it.isNotBlank() }
            ?.let { "$point(${encode(it)})" }
            ?: point
        return "geo:$point?q=$query"
    }

    /** True when [raw] holds coordinates a maps app could open. */
    fun isUsable(raw: String?): Boolean = parseCoordinates(raw) != null

    /**
     * Tolerant on purpose: accepts `44.05,5.04`, `44.05 ; 5.04`, `44,05 / 5,04` is NOT accepted (a
     * decimal comma is ambiguous with the separator), and rejects anything out of range.
     */
    private fun parseCoordinates(raw: String?): Pair<Double, Double>? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        val separator = SEPARATORS.firstOrNull { text.contains(it) } ?: return null
        val parts = text.split(separator)
        if (parts.size != 2) return null

        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lng = parts[1].trim().toDoubleOrNull() ?: return null
        if (lat !in LAT_RANGE || lng !in LNG_RANGE) return null
        return lat to lng
    }

    private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8.name())
}
