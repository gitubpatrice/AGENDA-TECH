package com.filestech.agenda_tech.data.local.db

import androidx.room.TypeConverter
import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.domain.model.RecurrenceFreq

/**
 * Room `TypeConverter`s mapping the domain enums to the `INTEGER` columns that store them.
 *
 * The converters are non-null; Room emits its own null-guard for the nullable columns
 * (`events.color_override`, `events.rrule_freq`), so a NULL cell never reaches [fromRaw].
 * Unknown ints are tolerated (the enums' own `fromRaw` fall back + log) — forward-compatible
 * with rows written by a newer build or restored from an `.ics` import.
 */
class AgendaEnumConverters {
    @TypeConverter
    fun calendarColorFromRaw(rawValue: Int): CalendarColor = CalendarColor.fromRaw(rawValue)

    @TypeConverter
    fun calendarColorToRaw(color: CalendarColor): Int = color.rawValue

    @TypeConverter
    fun recurrenceFreqFromRaw(rawValue: Int): RecurrenceFreq = RecurrenceFreq.fromRaw(rawValue)

    @TypeConverter
    fun recurrenceFreqToRaw(freq: RecurrenceFreq): Int = freq.rawValue
}
