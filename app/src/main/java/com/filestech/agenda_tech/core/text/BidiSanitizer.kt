package com.filestech.agenda_tech.core.text

/**
 * Strips Unicode bidirectional overrides and isolates (`U+202A..U+202E`, `U+2066..U+2069`) from text
 * ingested from an untrusted source (imported `.ics` files, the device Calendar Provider). These
 * controls can visually reorder characters to spoof a title/location — anti-spoofing hardening
 * (`.ics` SEC-ICS2 / device-import). Shared so the guard can never drift between import paths.
 */
object BidiSanitizer {

    private val BIDI_CONTROLS: Set<Char> =
        ((0x202A..0x202E) + (0x2066..0x2069)).map { it.toChar() }.toSet()

    /** Generous per-field ceiling for text ingested from an untrusted calendar (anti-DoS). */
    const val MAX_FIELD_CHARS = 4_000

    fun strip(text: String): String = text.filterNot { it in BIDI_CONTROLS }

    /** [strip] plus a length cap — the guard every untrusted-import text field should pass through. */
    fun stripAndCap(text: String): String = strip(text).take(MAX_FIELD_CHARS)
}
