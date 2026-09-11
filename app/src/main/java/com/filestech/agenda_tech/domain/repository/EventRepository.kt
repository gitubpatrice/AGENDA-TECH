package com.filestech.agenda_tech.domain.repository

import com.filestech.agenda_tech.domain.model.AgendaStats
import com.filestech.agenda_tech.domain.model.Event
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for [Event]s.
 *
 * Occurrence expansion (time zone / DST, `EXDATE`, per-occurrence overrides) is not done here — it
 * is the job of the `RecurrenceExpander` layered on top of [observeForExpansion].
 */
interface EventRepository {

    /**
     * Streams the events that are candidates for occurrence expansion over the window — non-recurring
     * events that overlap it, plus recurring events whose series could intersect it (base before the
     * window end, rule not yet ended). Feed the result to
     * [com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander] to get concrete occurrences;
     * [com.filestech.agenda_tech.domain.usecase.ObserveOccurrencesInRangeUseCase] does exactly that.
     */
    fun observeForExpansion(windowStartUtcMillis: Long, windowEndUtcMillis: Long): Flow<List<Event>>

    /** Streams the events belonging to a given calendar. */
    fun observeByCalendar(calendarId: Long): Flow<List<Event>>

    suspend fun getById(id: Long): Event?

    /** All events — used to export the whole agenda to `.ics`. */
    suspend fun getAll(): List<Event>

    /**
     * Streams every event, regardless of calendar visibility. Backs search, which deliberately spans
     * hidden calendars: answering "no results" for an event the user typed the exact title of would
     * read as "it does not exist" — in an agenda, that is how a double-booking happens.
     */
    fun observeAll(): Flow<List<Event>>

    /** Streams what the agenda holds and when it last changed — drives the backup/restore prompts. */
    fun observeStats(): Flow<AgendaStats>

    /** Streams every per-occurrence override (used to expand recurring series correctly). */
    fun observeOverrides(): Flow<List<Event>>

    /** Deletes the overrides of a recurring master (when the whole series is deleted). */
    suspend fun deleteOverridesForParent(parentId: Long)

    /** Atomically deletes a recurring master and all its overrides. */
    suspend fun deleteSeriesAtomic(parentId: Long)

    /** Atomically upserts [event] and deletes the event with [deleteId] (exclude-from-master + drop override). */
    suspend fun upsertAndDelete(event: Event, deleteId: Long)

    /**
     * Atomically saves a per-occurrence [override] and excludes [originalStartUtcMillis] — the instant
     * it replaces — from the rule of its master ([masterId]). Returns the override's id.
     *
     * One operation, because it is one fact: this occurrence moved. Split in two writes, an
     * interruption between them leaves the master still producing an occurrence that has already been
     * replaced — and the master's EXDATEs are exported verbatim to `.ics` and read by the reminder
     * scheduler, so a stale list is not a detail.
     */
    suspend fun upsertOverrideAtomic(override: Event, masterId: Long, originalStartUtcMillis: Long): Long

    /** Inserts or updates; returns the event's id (freshly generated on insert). */
    suspend fun upsert(event: Event): Long

    /** Atomically inserts/updates a batch of events (import). All or nothing — no half-populated import. */
    suspend fun upsertAll(events: List<Event>)

    /**
     * (source_uid → ids existants, par id croissant) des événements importés d'un calendrier.
     *
     * Une LISTE, et non un id unique (relecture externe du 2026-09-11). `source_uid` n'est pas
     * unique et ne peut pas l'être : en RFC 5545, un maître récurrent et chacune de ses
     * occurrences modifiées partagent leur `UID`. L'ancienne signature `Map<String, Long>`
     * venait d'un `associate {}` où la DERNIÈRE ligne gagnait — les autres devenaient
     * invisibles au ré-import, et rien ne pouvait donc les mettre à jour.
     *
     * L'ordre est ce qui rend le ré-import idempotent : le n-ième VEVENT d'un UID retrouve
     * toujours la n-ième ligne.
     */
    suspend fun sourceUidGroups(calendarId: Long): Map<String, List<Long>>

    suspend fun delete(id: Long)
}
