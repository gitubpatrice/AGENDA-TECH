package com.filestech.agenda_tech.ui.screens.timeline

import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.domain.recurrence.EventOccurrence
import java.time.LocalDate
import java.time.ZoneId

/** A calendar occurrence as the Day/Week timeline needs it (colour already resolved). */
data class TimelineItem(
    val eventId: Long,
    val title: String,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
    val allDay: Boolean,
    val colorArgb: Int,
)

/** Resolves each occurrence's colour (event override, else its calendar's) into a [TimelineItem]. */
fun List<EventOccurrence>.toTimelineItems(colorByCalendarId: Map<Long, Int>): List<TimelineItem> =
    map { occurrence ->
        TimelineItem(
            eventId = occurrence.event.id,
            title = occurrence.event.title,
            startUtcMillis = occurrence.startUtcMillis,
            endUtcMillis = occurrence.endUtcMillis,
            allDay = occurrence.event.allDay,
            colorArgb = occurrence.event.colorOverride?.argb
                ?: colorByCalendarId[occurrence.event.calendarId]
                ?: CalendarColor.DEFAULT.argb,
        )
    }

/** One day of the timeline: its all-day items (shown in a strip) and its positioned timed blocks. */
data class DayTimelineData(
    val date: LocalDate,
    val allDayItems: List<TimelineItem>,
    val positioned: List<PositionedItem<TimelineItem>>,
    val isToday: Boolean,
    /** Minutes since midnight for the "now" line, or null when [date] is not today. */
    val nowMinute: Int?,
)

/**
 * Pure builder that turns the occurrences overlapping a day into a [DayTimelineData] — splits
 * all-day from timed, projects timed items onto minute offsets within [date] (in [zone]) and runs
 * [DayLayout] for side-by-side lanes. No Android; unit-testable.
 */
object TimelineBuilder {

    fun build(
        items: List<TimelineItem>,
        date: LocalDate,
        zone: ZoneId,
        todayDate: LocalDate,
        nowUtcMillis: Long,
    ): DayTimelineData {
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()

        val allDayItems = items.filter { it.allDay }.sortedBy { it.startUtcMillis }
        val timed = items
            .filterNot { it.allDay }
            .map { item ->
                TimedItem(
                    item = item,
                    startMinute = minutesFrom(dayStart, item.startUtcMillis),
                    endMinute = minutesFrom(dayStart, item.endUtcMillis),
                )
            }

        val isToday = date == todayDate
        val nowMinute = if (isToday) minutesFrom(dayStart, nowUtcMillis) else null

        return DayTimelineData(
            date = date,
            allDayItems = allDayItems,
            positioned = DayLayout.layout(timed),
            isToday = isToday,
            nowMinute = nowMinute,
        )
    }

    private fun minutesFrom(dayStartUtcMillis: Long, instantUtcMillis: Long): Int =
        ((instantUtcMillis - dayStartUtcMillis) / 60_000L).toInt()
}
