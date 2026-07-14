package com.filestech.agenda_tech.domain.model

import timber.log.Timber

/**
 * Fixed, named palette for calendars and events. [rawValue] is the stable identifier persisted
 * in Room (never reorder existing entries — append only), [argb] is the sRGB colour the UI layer
 * turns into a `Color`. Keeping the palette closed (vs. a free-form ARGB) guarantees every colour
 * has an accessible on-colour and a name for the picker.
 */
enum class CalendarColor(val rawValue: Int, val argb: Int) {
    BLUEBERRY(0, 0xFF2460AB.toInt()),
    PEACOCK(1, 0xFF039BE5.toInt()),
    BASIL(2, 0xFF0B8043.toInt()),
    SAGE(3, 0xFF33B679.toInt()),
    BANANA(4, 0xFFE4C441.toInt()),
    TANGERINE(5, 0xFFF4511E.toInt()),
    TOMATO(6, 0xFFD50000.toInt()),
    FLAMINGO(7, 0xFFE67C73.toInt()),
    GRAPE(8, 0xFF8E24AA.toInt()),
    LAVENDER(9, 0xFF7986CB.toInt()),
    GRAPHITE(10, 0xFF616161.toInt()),
    ;

    companion object {
        val DEFAULT: CalendarColor = BLUEBERRY

        fun fromRaw(rawValue: Int): CalendarColor = entries.firstOrNull { it.rawValue == rawValue }
            ?: DEFAULT.also { Timber.w("Unknown CalendarColor int %d — defaulting to %s", rawValue, DEFAULT) }
    }
}
