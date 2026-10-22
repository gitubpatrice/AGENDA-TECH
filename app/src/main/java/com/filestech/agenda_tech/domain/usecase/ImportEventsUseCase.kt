package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.domain.ImportLimits
import com.filestech.agenda_tech.domain.ics.ICS_SOURCE_PREFIX
import com.filestech.agenda_tech.domain.ics.IcsCodec
import com.filestech.agenda_tech.domain.ics.toEvent
import com.filestech.agenda_tech.domain.ics.updatedFrom
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.share.EventShare
import com.filestech.agenda_tech.domain.repository.EventRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses an `.ics` document and inserts its events into the default calendar — or, for an event the
 * agenda already holds under that UID, updates it where it is. Returns the number imported (0 if the
 * file has no valid VEVENT or no calendar exists yet). Reminders/VALARM are not imported (out of scope
 * for this codec).
 */
// @Singleton, et pas par commodité : un Mutex d'instance ne sérialise rien si Hilt fabrique un
// exemplaire par injection. Le jumeau ImportDeviceEventsUseCase porte la même annotation pour la
// même raison (audit AG-14).
@Singleton
class ImportEventsUseCase @Inject constructor(
    private val eventRepository: EventRepository,
    private val calendarRepository: CalendarRepository,
) {
    /**
     * The file holds more events than [ImportLimits.MAX_EVENTS].
     *
     * A distinct type, not a generic failure: the screen can then say *why* the file was refused, and
     * "too large" is the one import failure the user can actually do something about.
     */
    class TooManyEvents(val found: Int) :
        Exception("ics file holds $found events, over the ${ImportLimits.MAX_EVENTS} ceiling")

    /**
     * Sérialise les imports (audit AG-14).
     *
     * Le jumeau [ImportDeviceEventsUseCase] porte ce mutex depuis l'audit U3, avec sa raison :
     * `sourceUidMap` puis `upsertAll` est une lecture-puis-écriture, et deux passes concurrentes
     * lisent chacune une carte où l'autre n'a rien écrit — donc insèrent chacune leur jeu de lignes.
     * Le même motif vivait ici sans garde, et l'écran d'import ne bloque pas son bouton. Relancer le
     * même fichier deux fois de suite suffisait à dupliquer, alors que l'idempotence est la propriété
     * annoncée en tête de cette classe.
     */
    private val mutex = Mutex()

    suspend operator fun invoke(icsText: String, defaultZoneId: String): Int = mutex.withLock {
        val calendars = calendarRepository.observeAll().first()
        val targetId = (calendars.firstOrNull { it.isDefault } ?: calendars.firstOrNull())?.id ?: return@withLock 0

        val zone = runCatching { ZoneId.of(defaultZoneId) }.getOrDefault(ZoneId.systemDefault())
        val parsed = IcsCodec.decode(icsText, zone)
        // Audit S12 — refused WHOLE, before a single row is written. The byte ceiling upstream bounds
        // what is read into memory; it says nothing about how many rows reach the database, and 5 MiB
        // of minimal VEVENT blocks is some 87 000 of them. Importing part of the file would look
        // exactly like importing all of it.
        if (parsed.size > ImportLimits.MAX_EVENTS) throw TooManyEvents(parsed.size)

        // FIAB-1 — idempotent re-import: an event whose VEVENT UID the agenda already holds updates that
        // row instead of adding a duplicate (events without a UID still insert).
        //
        // 2026-10-22 — "already holds" was "already imported into the default calendar", and the agenda's
        // OWN export did not come back through it: an event created here left under a UID nothing
        // answered to, a re-import added a copy, and each round trip one more. The index now holds every
        // calendar and every spelling the export writes (see [uidsRecognised]); the update keeps the row's
        // calendar and what the file does not carry ([updatedFrom]).
        val existing = HashMap<String, MutableList<Event>>()
        eventRepository.getAll().sortedBy { it.id }.forEach { row ->
            uidsRecognised(row).forEach { uid -> existing.getOrPut(uid) { mutableListOf() } += row }
        }
        // Audit AG-14 — une ligne existante ne peut être revendiquée QU'UNE FOIS par import.
        //
        // En RFC 5545, un maître récurrent et chacune de ses occurrences modifiées PARTAGENT leur
        // UID (elles se distinguent par RECURRENCE-ID, que ce codec ne lit pas encore — cf. la KDoc
        // d'IcsCodec). Sans ce garde, N VEVENT de même UID recevaient tous le même `existingId` :
        // `upsertAll` les écrasait les uns après les autres sur une seule ligne, les N-1 autres
        // restaient en base périmées et jamais mises à jour, et le compte retourné annonçait quand
        // même N importés. L'idempotence promise en tête de cette classe n'était donc vraie que pour
        // un fichier dont tous les UID sont distincts.
        //
        // Le n-ieme VEVENT d'un UID retrouve la n-ieme ligne existante de cet UID. C'est ce qui
        // rend le re-import IDEMPOTENT malgre des UID dupliques : au premier import les N lignes
        // s'inserent, aux suivants chacune retrouve la sienne.
        //
        // ⚠️ Une premiere version de ce correctif se contentait d'un HashSet d'ids « deja
        // revendiques » par-dessus une Map<String, Long>. Elle empechait bien l'ecrasement, et
        // introduisait PIRE : la carte ne pouvant porter qu'un id par UID, le deuxieme VEVENT ne
        // trouvait jamais sa ligne et s'inserait a neuf — a CHAQUE re-import. L'agenda grossissait
        // d'une copie par passage, la ou le defaut d'origine, lui, stagnait. Signale par la
        // relecture gpt-5.2 du 2026-09-11 ; c'est la pluralite cote BASE qui manquait, pas un
        // garde cote fichier.
        //
        // Revendiquer « la premiere ligne libre » plutot que « la n-ieme » (2026-10-22) : une ligne se
        // trouve desormais sous deux cles (`abc123` et `abc123@filestech`), et un rang compte par cle
        // laisserait deux VEVENT la revendiquer chacun par la sienne. Pour une seule cle, c'est le meme
        // appariement.
        val claimed = HashSet<Long>()
        val mapped = parsed.map { icsEvent ->
            val row = icsEvent.uid?.let { uid -> existing[uid]?.firstOrNull { it.id !in claimed } }
            if (row != null) {
                claimed += row.id
                row.updatedFrom(icsEvent, placesWritten = listOf(row.location, EventShare.place(row)))
            } else {
                icsEvent.toEvent(targetId)
            }
        }
        eventRepository.upsertAll(mapped) // PERF-1 — single atomic batch, not N transactions
        return@withLock mapped.size
    }

    /**
     * The UIDs a file can name [row] by: its iCalendar UID, and that UID as the export writes it
     * ([IcsCodec.uidAsWritten] gives a domain to one that has none).
     *
     * None for an event with no identity yet — no file has carried it — and none for a copy of a device
     * event: its uid is the provider's `_SYNC_ID` (or `rowid:<n>`), which no standard makes unique across
     * phones — an Exchange id reads `2:5` — so another phone's file could name an unrelated event by it.
     * Such a copy still comes back as a copy, as it did up to 1.3.0.
     */
    private fun uidsRecognised(row: Event): Set<String> {
        val uid = row.sourceUid?.takeIf { it.startsWith(ICS_SOURCE_PREFIX) } ?: return emptySet()
        val own = uid.removePrefix(ICS_SOURCE_PREFIX)
        return setOf(own, IcsCodec.uidAsWritten(own))
    }
}
