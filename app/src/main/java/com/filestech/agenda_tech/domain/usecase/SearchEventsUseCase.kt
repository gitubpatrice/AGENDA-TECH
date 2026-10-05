package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.core.text.SearchText
import com.filestech.agenda_tech.core.di.DefaultDispatcher
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.recurrence.ALL_DAY_PLACEMENT_BOUND_MILLIS
import com.filestech.agenda_tech.domain.recurrence.ExpansionBudget
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander
import com.filestech.agenda_tech.domain.recurrence.shownStartUtcMillis
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.repository.EventRepository
import com.filestech.agenda_tech.domain.search.EventSearchHit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import java.time.ZoneId
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
     * [zones] is the phone's zone as it changes: a change re-runs the search, whose upcoming/past split
     * and order depend on it for all-day events. [nowUtcMillis] is a parameter rather than a direct
     * clock read so a test can pin "now" and assert that split deterministically.
     */
    operator fun invoke(
        queries: Flow<String>,
        zones: Flow<ZoneId>,
        nowUtcMillis: () -> Long = System::currentTimeMillis,
    ): Flow<List<EventSearchHit>> {
        val corpus = combine(
            eventRepository.observeAll(),
            calendarRepository.observeAll(),
            eventRepository.observeOverrides(),
        ) { events, calendars, overrides -> buildCorpus(events, calendars, overrides) }

        return combine(corpus, queries, zones) { entries, query, zone ->
            search(entries, query, nowUtcMillis(), zone)
        }.flowOn(defaultDispatcher)
    }

    /**
     * One event, with its searchable text pre-folded and the instants its own overrides replace.
     *
     * [excludedStarts] is derived from the live overrides rather than read off the master's stored
     * `EXDATE`s — the same mechanism the calendar views use
     * ([com.filestech.agenda_tech.domain.usecase.ObserveOccurrencesInRangeUseCase]). Trusting the
     * master's stored list instead would make search the only reader with its own answer to
     * "does this occurrence still exist", and it would date a master to an occurrence the user has
     * already moved away.
     */
    private data class Entry(
        val event: Event,
        val calendar: Calendar,
        val haystack: String,
        val excludedStarts: Set<Long>,
    )

    private fun buildCorpus(
        events: List<Event>,
        calendars: List<Calendar>,
        overrides: List<Event>,
    ): List<Entry> {
        val byId = calendars.associateBy { it.id }
        val excludedByParent = overrides
            .groupBy { it.recurrenceParentId }
            .mapValues { (_, list) -> list.mapNotNull { it.originalStartUtcMillis }.toHashSet() }

        return events.mapNotNull { event ->
            // An event with no calendar cannot be shown (no colour, no name, and tapping it would
            // open an editor with a dangling parent). The DB's foreign key makes this unreachable;
            // dropping it beats rendering a broken row if it ever happens.
            val calendar = byId[event.calendarId] ?: return@mapNotNull null
            val excluded = if (event.isRecurring) excludedByParent[event.id].orEmpty() else emptySet()
            Entry(event, calendar, foldedHaystack(event), excluded)
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

    private fun search(entries: List<Entry>, query: String, nowUtcMillis: Long, zone: ZoneId): List<EventSearchHit> {
        val needle = SearchText.fold(query.trim())
        // An empty query returns nothing, never everything: dumping the whole agenda the moment the
        // field is focused would bury the one thing being looked for.
        if (needle.isEmpty()) return emptyList()

        // Audit F5 — one allowance for the whole keystroke. `SearchViewModel` deliberately does not
        // debounce, on the stated grounds that "typing only runs a contains over pre-folded strings —
        // there is no expensive work to throttle". That was only true of the matching: `dateHit` below
        // expands a recurrence per HIT, up to the per-event scan cap each time. A common letter matching
        // hundreds of old recurring events therefore cost millions of java.time operations per
        // keystroke, on a computation with no suspension point, so cancellation could not even interrupt
        // it. Off the main thread (`flowOn(defaultDispatcher)`), so never an ANR — just a search that
        // stops answering and a battery that drains.
        val budget = ExpansionBudget(SEARCH_MAX_ITERATIONS)

        val hits = entries.mapNotNull { entry ->
            if (!entry.haystack.contains(needle)) return@mapNotNull null
            dateHit(entry, nowUtcMillis, zone, budget)
        }

        // Upcoming first, soonest first — "when is my dentist?". Then the past, most recent first —
        // "when *was* it?". Those are the two questions people search an agenda with. Ordered by where
        // each occurrence begins on the phone's calendar, as the views order them.
        val (upcoming, past) = hits.partition { it.isUpcoming }
        val shownStart = { hit: EventSearchHit -> hit.event.shownStartUtcMillis(hit.occurrenceStartUtcMillis, zone) }
        return upcoming.sortedBy(shownStart) + past.sortedByDescending(shownStart)
    }

    /**
     * Dates a hit. A recurring event has no single date: show the next occurrence, or the last one if
     * the series is over. Falling back to the master's base start would date a weekly meeting to the
     * day it was created.
     */
    private fun dateHit(entry: Entry, nowUtcMillis: Long, zone: ZoneId, budget: ExpansionBudget): EventSearchHit? {
        val event = entry.event
        // Upcoming means "begins after now on the phone's calendar". For an all-day occurrence that is
        // the phone's midnight of its date, up to the bound away from its stored start once the phone
        // has left its zone (AllDayPlacement): compared by stored start, tomorrow's bin day after a
        // flight west was dated to the week after. The walk therefore starts and ends that much wider.
        val margin = if (event.allDay) ALL_DAY_PLACEMENT_BOUND_MILLIS else 0L
        val beginsAfterNow = { start: Long -> event.shownStartUtcMillis(start, zone) >= nowUtcMillis }
        expander.firstOccurrenceStart(event, nowUtcMillis - margin, entry.excludedStarts, budget, beginsAfterNow)
            ?.let { next -> return EventSearchHit(event, entry.calendar, next, isUpcoming = true) }
        expander.lastOccurrenceStartBefore(event, nowUtcMillis + margin, entry.excludedStarts, budget) { !beginsAfterNow(it) }
            ?.let { last -> return EventSearchHit(event, entry.calendar, last, isUpcoming = false) }
        // Neither ahead nor behind: every occurrence was excluded (EXDATE), the series has no instance
        // left to point at — or the pass budget ran out. Nothing truthful to show either way.
        return null
    }

    private companion object {
        /**
         * Far more generous than a render pass ([ExpansionBudget.DEFAULT_MAX_ITERATIONS]): a search walks
         * a series from its base to *today* rather than over a visible window, so a legitimate old daily
         * event costs thousands of iterations on its own. High enough that a real agenda never reaches
         * it, low enough that a hostile import cannot turn one keystroke into minutes of arithmetic.
         */
        const val SEARCH_MAX_ITERATIONS = 5_000_000
    }
}
