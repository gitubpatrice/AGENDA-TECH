package com.filestech.agenda_tech.domain.model

import timber.log.Timber

/**
 * RFC 5545 `FREQ` values relevant to a personal agenda. The *absence* of recurrence is modelled
 * by a null [RecurrenceRule] on the event — there is deliberately no `NONE` member here.
 *
 * [rawValue] is persisted in Room; append-only, never reorder.
 */
enum class RecurrenceFreq(val rawValue: Int) {
    DAILY(0),
    WEEKLY(1),
    MONTHLY(2),
    YEARLY(3),
    ;

    companion object {
        fun fromRaw(rawValue: Int): RecurrenceFreq = entries.firstOrNull { it.rawValue == rawValue }
            ?: DAILY.also { Timber.w("Unknown RecurrenceFreq int %d — defaulting to DAILY", rawValue) }
    }
}
