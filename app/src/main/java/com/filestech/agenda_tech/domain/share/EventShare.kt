package com.filestech.agenda_tech.domain.share

import com.filestech.agenda_tech.domain.ics.IcsCodec
import com.filestech.agenda_tech.domain.ics.toIcsEvent
import com.filestech.agenda_tech.domain.location.GeoLink
import com.filestech.agenda_tech.domain.location.PlaceLabel
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.recurrence.dateZone
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * One event as the editor shows it: the row the agenda holds, and where the occurrence the user opened
 * starts and ends — for a series, the occurrence tapped, not the first one.
 */
data class ShareSource(val event: Event, val startUtcMillis: Long, val endUtcMillis: Long)

/**
 * What "Share" sends: a few lines anyone can read in a message, or an `.ics` file another calendar can
 * add. Pure — no Android — hence unit-testable; the editor hands the result to the phone's share sheet.
 *
 * Both carry the place in full — name, street, postal code and city — since the person receiving it has
 * none of the editor's separate fields. That is the one difference with the agenda's `.ics` export, whose
 * `LOCATION` is the place name alone: imported back into this app, a full line there would sit beside the
 * address fields it was made from.
 */
object EventShare {

    /**
     * ```
     * Dentiste
     * Mardi 13 octobre 2026, 09:00 – 10:00
     * Cabinet du Dr Martin, 12 rue des Lices, 84000 Avignon
     *
     * Apporter la carte vitale.
     * ```
     * [title] comes in as the screen shows it (a birthday's age appended), [allDayLabel] in the app's
     * language: both need resources this object does not have.
     */
    fun text(source: ShareSource, deviceZone: ZoneId, locale: Locale, title: String, allDayLabel: String): String =
        buildList {
            add(title.trim())
            add(whenLine(source, deviceZone, locale, allDayLabel))
            place(source.event)?.let(::add)
            source.event.description?.trim()?.takeIf { it.isNotEmpty() }?.let {
                add("")
                add(it)
            }
        }.joinToString("\n")

    /**
     * The event as an `.ics` document. A series travels whole — its rule, its cancelled dates, and its
     * moved occurrences given in [overrides] — as the agenda's own export writes it.
     */
    fun ics(event: Event, overrides: List<Event>, nowUtcMillis: Long): String =
        IcsCodec.encode((listOf(event) + overrides).map { it.toIcsEvent().copy(location = place(it)) }, nowUtcMillis)

    /**
     * A file name for the `.ics`, from the title: letters, digits, spaces, `-` and `_` only — no `/`, no
     * `.`, so a title can never name a path — at most [MAX_FILE_STEM] characters, `event` when nothing is
     * left.
     */
    fun fileStem(title: String): String =
        title.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .take(MAX_FILE_STEM)
            .trim()
            .ifEmpty { "event" }

    private fun place(event: Event): String? =
        PlaceLabel.forSharing(event) ?: event.gpsCoordinates?.trim()?.takeIf(GeoLink::isUsable)

    /** The day and the hours, read the way the editor reads them: an all-day event's dates in its own zone. */
    private fun whenLine(source: ShareSource, deviceZone: ZoneId, locale: Locale, allDayLabel: String): String {
        val zone = source.event.dateZone(deviceZone)
        val start = Instant.ofEpochMilli(source.startUtcMillis).atZone(zone)
        val end = Instant.ofEpochMilli(source.endUtcMillis).atZone(zone)
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        val firstDay = start.format(date).capitalized(locale)

        if (source.event.allDay) {
            // The end is the midnight after the last date: that date is the day before it.
            val endDate = if (end.toLocalTime() == LocalTime.MIDNIGHT) end.toLocalDate().minusDays(1) else end.toLocalDate()
            val lastDate = maxOf(endDate, start.toLocalDate())
            val days = if (lastDate == start.toLocalDate()) firstDay else "$firstDay – ${lastDate.format(date)}"
            return "$days · $allDayLabel"
        }
        return if (end.toLocalDate() == start.toLocalDate()) {
            "$firstDay, ${start.format(time)} – ${end.format(time)}"
        } else {
            "$firstDay, ${start.format(time)} – ${end.format(date)}, ${end.format(time)}"
        }
    }

    private fun String.capitalized(locale: Locale): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

    const val MAX_FILE_STEM = 40
}
