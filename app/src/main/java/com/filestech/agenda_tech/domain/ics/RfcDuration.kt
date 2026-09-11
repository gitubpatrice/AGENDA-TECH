package com.filestech.agenda_tech.domain.ics

import com.filestech.agenda_tech.core.time.DAY_MILLIS

/**
 * RFC 5545 §3.3.6 durations (`P1D`, `PT1H30M`, `PT3600S`, `P2W`) → milliseconds.
 *
 * ## Why this is its own object (audit AG-1)
 *
 * Two ingestion paths need it and only one had it. The Calendar Provider stores a `duration` column
 * in RFC form for recurring events, so [com.filestech.agenda_tech.domain.device.DeviceEventMapper]
 * has parsed it since day one; [IcsCodec] never did, and a `VEVENT` carrying `DURATION` instead of
 * `DTEND` — which RFC 5545 §3.6.1 explicitly allows — collapsed to a zero-length event that no view
 * could show. Copying the parser into the codec would have made it the second of two, which is how
 * the two answers drift apart; it lives here instead, and both callers ask the same question.
 *
 * **Every failure answers zero, never an exception.** The input is untrusted on both paths (a file
 * the user picked, a provider row written by another app), and the callers each have their own idea
 * of what to do with "no usable duration" — the device mapper falls back to an hour, the codec to a
 * day for an all-day event. Deciding that here would take the choice away from the only two places
 * that can make it correctly.
 */
internal object RfcDuration {

    /** Per-component bound, applied *before* the cascade so a pathological `P999999999D` cannot overflow. */
    private const val MAX_DAYS = 3_650L // ~10 years

    /** Anything longer than the per-component bound in total is bogus, whatever the components say. */
    private const val MAX_MILLIS = MAX_DAYS * DAY_MILLIS

    /** Optional sign tolerated but ignored: durations here are always positive event lengths. */
    private val PATTERN =
        Regex("[+-]?P(?:(\\d+W)|)(?:(\\d+D)|)(?:T(?:(\\d+H)|)(?:(\\d+M)|)(?:(\\d+S)|))?")

    /** Milliseconds, or **0** when [duration] is absent, malformed or out of range. */
    fun parseMillis(duration: String?): Long {
        val raw = duration?.trim().orEmpty()
        if (raw.isEmpty()) return 0L
        val match = PATTERN.matchEntire(raw) ?: return 0L
        val (weeks, days, hours, minutes, seconds) = match.destructured
        fun component(token: String) = token.dropLast(1).toLongOrNull()?.coerceIn(0, MAX_DAYS) ?: 0
        val w = component(weeks)
        val d = component(days)
        val h = component(hours)
        val mi = component(minutes)
        val se = component(seconds)
        val millis = ((((w * 7 + d) * 24 + h) * 60 + mi) * 60 + se) * 1000
        return millis.coerceIn(0, MAX_MILLIS)
    }
}
