package com.filestech.agenda_tech.ui.util

import android.content.Context
import com.filestech.agenda_tech.R

private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR

/**
 * How a reminder reads — "At time of event", "15 min before", "2 h before", "1 day before". A negative
 * value is the settings' "no default reminder".
 *
 * One function for the editor and the settings, which each had their own copy; the day count is a
 * plural, where both copies wrote "1 day(s) before".
 */
internal fun reminderLabel(context: Context, minutes: Int): String = when {
    minutes < 0 -> context.getString(R.string.reminder_off)
    minutes == 0 -> context.getString(R.string.reminder_at_time)
    minutes % MINUTES_PER_DAY == 0 -> (minutes / MINUTES_PER_DAY).let { days ->
        context.resources.getQuantityString(R.plurals.reminder_days, days, days)
    }
    minutes % MINUTES_PER_HOUR == 0 -> context.getString(R.string.reminder_hours, minutes / MINUTES_PER_HOUR)
    else -> context.getString(R.string.reminder_minutes, minutes)
}
