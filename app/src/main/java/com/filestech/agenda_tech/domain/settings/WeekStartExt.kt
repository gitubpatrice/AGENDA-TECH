package com.filestech.agenda_tech.domain.settings

import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale

/** Resolves a [WeekStart] preference to a concrete [DayOfWeek]; SYSTEM follows [locale]. */
fun WeekStart.toDayOfWeek(locale: Locale): DayOfWeek = when (this) {
    WeekStart.SYSTEM -> WeekFields.of(locale).firstDayOfWeek
    WeekStart.MONDAY -> DayOfWeek.MONDAY
    WeekStart.SATURDAY -> DayOfWeek.SATURDAY
    WeekStart.SUNDAY -> DayOfWeek.SUNDAY
}
