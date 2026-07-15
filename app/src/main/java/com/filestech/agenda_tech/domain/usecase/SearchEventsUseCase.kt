package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.core.text.SearchText
import com.filestech.agenda_tech.di.DefaultDispatcher
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.repository.EventRepository
import com.filestech.agenda_tech.domain.search.EventSearchHit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

/**
 * Free-text search over the whole agenda, accent- and case-insensitive.
 *
 * **In memory, not in SQL.** SQLite's `LIKE` only case-folds ASCII and ignores accents entirely, so
 * `reunion` would never find `Réunion` — the exact search a French user types. Fixing that in SQL
 * would mean a denormalised folded column, a migration, and keeping that column in step with every
 * write. A personal agenda is a few thousand rows: folding it here is simpler and cannot fall out of
 * sync. If a corpus ever outgrows this, the folded column is the upgrade path.
 *
 * The corpus is folded **once per data change**, not once per keystroke: [combine] only re-runs
 * [buildCorpus] when the events or calendars actually change, while typing merely re-filters.
 *
 * **Spans hidden calendars on purpose.** The views drop events of calendars toggled off; search does
 * not. Hiding a calendar means "keep it out of my week", not "pretend it never happened" — and a
 * silent omission would read as "this event does not exist", which is how a double-booking starts.
 * Each hit carries its calendar so the user can see where it lives.
 */
class SearchEventsUseCase @Inject constructor(
    private val eventRepository: EventRepository,
    private val calendarRepository: CalendarRepository,
    private val expander: RecurrenceExpander,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) {

    /**
     * Streams the hits for each emitted query. Debouncing belongs to the caller.
     *
     * [nowUtcMillis] is a parameter rather than a direct clock read so a test can pin "now" and
     * assert the upcoming/past split deterministically.
     */
    operator fun invoke(
        queries: Flow<String>,
        nowUtcMillis: () -> Long = System::currentTimeMillis,
    ): Flow<List<EventSearchHit>> {
        val corpus = combine(
            eventRepository.observeAll(),
            calendarRepository.observeAll(),
        ) { events, calendars -> buildCorpus(events, calendars) }

        return combine(corpus, queries) { entries, query ->
            search(entries, query, nowUtcMillis())
        }.flowOn(defaultDispatcher)
    }

    /** One event, with its searchable text pre-folded. */
    internal data class Entry(val event: Event, val calendar: Calendar, val haystack: String)

    internal fun buildCorpus(events: List<Event>, calendars: List<Calendar>): List<Entry> {
        val byId = calendars.associateBy { it.id }
        return events.mapNotNull { event ->
            // An event with no calendar cannot be shown (no colour, no name, and tapping it would
            // open an editor with a dangling parent). The DB's foreign key makes this unreachable;
            // dropping it beats rendering a broken row if it ever happens.
            val calendar = byId[event.calendarId] ?: return@mapNotNull null
            Entry(event, calendar, foldedHaystack(event))
        }
    }

    /**
     * The searchable text of an event, folded once.
     *
     * Fields are joined by a newline rather than concatenated: gluing a title to a location would
     * invent matches that span the seam (title `Réu` + city `Nice` must not match `réunice`).
     * GPS coordinates are left out — nobody searches an agenda by latitude.
     */
    private fun foldedHaystack(event: Event): String = SearchText.fold(
        listOfNotNull(
            event.title,
            event.description,
            event.location,
            event.address,
            event.city,
        ).joinToString("\n"),
    )

    internal fun search(entries: List<Entry>, query: String, nowUtcMillis: Long): List<EventSearchHit> {
        val needle = SearchText.fold(query.trim())
        // An empty query returns nothing, never everything: dumping the whole agenda the moment the
        // field is focused would bury the one thing being looked for.
        if (needle.isEmpty()) return emptyList()

        val hits = entries.mapNotNull { entry ->
            if (!entry.haystack.contains(needle)) return@mapNotNull null
            dateHit(entry, nowUtcMillis)
        }

        // Upcoming first, soonest first — "when is my dentist?". Then the past, most recent first —
        // "when *was* it?". Those are the two questions people search an agenda with.
        val (upcoming, past) = hits.partition { it.isUpcoming }
        return upcoming.sortedBy { it.occurrenceStartUtcMillis } +
            past.sortedByDescending { it.occurrenceStartUtcMillis }
    }

    /**
     * Dates a hit. A recurring event has no single date: show the next occurrence, or the last one if
     * the series is over. Falling back to the master's base start would date a weekly meeting to the
     * day it was created.
     */
    private fun dateHit(entry: Entry, nowUtcMillis: Long): EventSearchHit? {
        expander.nextOccurrenceStart(entry.event, nowUtcMillis)?.let { next ->
            return EventSearchHit(entry.event, entry.calendar, next, isUpcoming = true)
        }
        expander.lastOccurrenceStartBefore(entry.event, nowUtcMillis)?.let { last ->
            return EventSearchHit(entry.event, entry.calendar, last, isUpcoming = false)
        }
        // Neither ahead nor behind: every occurrence was excluded (EXDATE), so the series has no
        // instance left to point at. Nothing truthful to show.
        return null
    }
}
