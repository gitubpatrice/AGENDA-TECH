package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.domain.ImportLimits
import com.filestech.agenda_tech.domain.ics.IcsCodec
import com.filestech.agenda_tech.domain.ics.toEvent
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.repository.EventRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses an `.ics` document and inserts its events into the default calendar. Returns the number
 * imported (0 if the file has no valid VEVENT or no calendar exists yet). Reminders/VALARM are not
 * imported (out of scope for this codec).
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

        // FIAB-1 — idempotent re-import: an event whose VEVENT UID was already imported updates the
        // same row instead of adding a duplicate (events without a UID still insert). Same pattern
        // as the device-calendar import.
        val existing = eventRepository.sourceUidGroups(targetId)
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
        val consumed = HashMap<String, Int>()
        val mapped = parsed.map { icsEvent ->
            val event = icsEvent.toEvent(targetId)
            val existingId = event.sourceUid?.let { uid ->
                val rank = consumed.getOrDefault(uid, 0)
                consumed[uid] = rank + 1
                existing[uid]?.getOrNull(rank)
            }
            if (existingId != null) event.copy(id = existingId) else event
        }
        eventRepository.upsertAll(mapped) // PERF-1 — single atomic batch, not N transactions
        return@withLock mapped.size
    }
}
