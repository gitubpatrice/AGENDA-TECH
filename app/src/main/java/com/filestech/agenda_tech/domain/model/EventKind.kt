package com.filestech.agenda_tech.domain.model

import timber.log.Timber

/**
 * What an event *is*, as opposed to what it contains.
 *
 * Only [BIRTHDAY] is distinguished so far, and it is a real column rather than a guess made from the
 * shape of the event. An all-day, yearly, endless event is not necessarily a birthday — it is also
 * how one writes "renew the insurance" — so inferring the kind would put a cake on the wrong rows
 * and, worse, would silently change meaning the day a user edits the recurrence.
 *
 * Unknown raw values fall back to [NORMAL] rather than throwing: a row written by a newer build, or
 * restored from a newer backup, must still open.
 */
enum class EventKind(val rawValue: Int) {
    NORMAL(0),
    BIRTHDAY(1),
    ;

    companion object {
        fun fromRaw(rawValue: Int): EventKind = entries.firstOrNull { it.rawValue == rawValue }
            ?: NORMAL.also { Timber.w("Unknown EventKind %d — defaulting to NORMAL", rawValue) }
    }
}
