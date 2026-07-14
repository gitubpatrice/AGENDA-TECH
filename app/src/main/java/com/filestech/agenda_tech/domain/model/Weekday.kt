package com.filestech.agenda_tech.domain.model

import timber.log.Timber

/**
 * Day of week, ISO-8601 numbered (Monday = 1 … Sunday = 7) so [isoValue] maps 1:1 to
 * `java.time.DayOfWeek.value`. Used by [RecurrenceRule.byWeekdays] for weekly `BYDAY` rules.
 */
enum class Weekday(val isoValue: Int) {
    MONDAY(1),
    TUESDAY(2),
    WEDNESDAY(3),
    THURSDAY(4),
    FRIDAY(5),
    SATURDAY(6),
    SUNDAY(7),
    ;

    companion object {
        fun fromIso(isoValue: Int): Weekday = entries.firstOrNull { it.isoValue == isoValue }
            ?: MONDAY.also { Timber.w("Unknown Weekday iso %d — defaulting to MONDAY", isoValue) }
    }
}
