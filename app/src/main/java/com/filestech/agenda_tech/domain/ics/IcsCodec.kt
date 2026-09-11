package com.filestech.agenda_tech.domain.ics

import com.filestech.agenda_tech.core.time.DAY_MILLIS
import com.filestech.agenda_tech.domain.model.EventKind
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.core.text.BidiSanitizer
import com.filestech.agenda_tech.core.time.TimeZones
import com.filestech.agenda_tech.domain.model.Weekday
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pure RFC 5545 (`.ics`) codec for the subset Agenda Tech uses — VEVENT with DTSTART/DTEND,
 * SUMMARY/DESCRIPTION/LOCATION, RRULE (FREQ/INTERVAL/BYDAY/COUNT/UNTIL) and EXDATE. No Android;
 * exhaustively unit-testable, and lossless on its own round-trip (time zones preserved via the
 * `TZID` parameter).
 *
 * Deliberate scope limits (documented, safe): no VTIMEZONE block is emitted (the `TZID` name is
 * enough for our own round-trip and for well-known zones in other apps), and VALARM/reminders are
 * not exported. Import is tolerant: unknown properties are ignored, lines are unfolded, and both
 * UTC (`…Z`), zoned (`TZID=`) and floating date-times are accepted.
 *
 * **`RECURRENCE-ID` n'est pas lu** (audit AG-14). Cette limite manquait à la liste ci-dessus, et son
 * absence coûtait plus qu'elle-même : en RFC 5545, un maître récurrent et chacune de ses occurrences
 * modifiées partagent leur `UID` et ne se distinguent QUE par cette propriété. Un fichier qui en
 * contient est donc lu comme N événements de même UID — ce que [ImportEventsUseCase] sait désormais
 * traiter sans écraser N-1 lignes, mais sans reconstituer la relation maître/dérogation pour autant.
 * Les occurrences modifiées arrivent comme des événements ordinaires. C'est visible et corrigeable à
 * la main ; le dire ici évite qu'on le redécouvre en croyant à un bug de l'import.
 *
 * The **line syntax** it is written in — folding, unfolding, TEXT escaping, parameter quoting,
 * splitting a content line — lives in [IcsLines]. This object owns only what a calendar means.
 */
object IcsCodec {

    private const val PRODID = "-//Files Tech//Agenda Tech//EN"

    /**
     * Non-standard property carrying [EventKind]. RFC 5545 §3.8.8.2 reserves the `X-` space for
     * exactly this; a reader that does not know it ignores the line, so a birthday exported to
     * Google or Thunderbird simply arrives as the yearly all-day event it already is.
     */
    private const val PROP_KIND = "X-AGENDA-TECH-KIND"

    /** Durée d'un VEVENT horodaté dépourvu de DTEND et de DURATION (audit AG-1). */
    private const val DEFAULT_DURATION_MILLIS = 60L * 60 * 1000

    /**
     * Minuit local suivant [startUtcMillis], en arithmétique calendaire.
     *
     * Audit AG-1 — la borne de fin d'une journée entière ne peut pas s'obtenir en ajoutant 24 h :
     * une nuit de changement d'heure dure 23 ou 25 heures, et la borne tomberait alors une heure
     * avant ou après le vrai minuit. `plusDays(1)` sur la date locale donne la bonne réponse dans
     * les trois cas, comme le fait déjà [com.filestech.agenda_tech.domain.device.DeviceEventMapper].
     */
    private fun nextMidnightAfter(startUtcMillis: Long, zone: ZoneId): Long =
        midnightAfterDays(startUtcMillis, zone, days = 1)

    /** Minuit local [days] jours apres [startUtcMillis], en arithmetique calendaire. */
    private fun midnightAfterDays(startUtcMillis: Long, zone: ZoneId, days: Long): Long =
        Instant.ofEpochMilli(startUtcMillis)
            .atZone(zone)
            .toLocalDate()
            .plusDays(days)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    private val UTC_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val LOCAL_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd")

    private val ISO_TO_BYDAY = mapOf(
        Weekday.MONDAY to "MO", Weekday.TUESDAY to "TU", Weekday.WEDNESDAY to "WE",
        Weekday.THURSDAY to "TH", Weekday.FRIDAY to "FR", Weekday.SATURDAY to "SA", Weekday.SUNDAY to "SU",
    )
    private val BYDAY_TO_ISO = ISO_TO_BYDAY.entries.associate { (k, v) -> v to k }

    // --- Encode --------------------------------------------------------------

    fun encode(events: List<IcsEvent>, nowUtcMillis: Long): String {
        val out = StringBuilder()
        out.appendContentLine("BEGIN:VCALENDAR")
        out.appendContentLine("VERSION:2.0")
        out.appendContentLine("PRODID:$PRODID")
        out.appendContentLine("CALSCALE:GREGORIAN")
        events.forEachIndexed { index, event -> appendVEvent(out, event, index, nowUtcMillis) }
        out.append("END:VCALENDAR").append(IcsLines.CRLF)
        return out.toString()
    }

    private fun StringBuilder.appendContentLine(line: String) {
        append(IcsLines.fold(line)).append(IcsLines.CRLF)
    }

    private fun appendVEvent(out: StringBuilder, event: IcsEvent, index: Int, nowUtcMillis: Long) {
        out.appendContentLine("BEGIN:VEVENT")
        // Stable UID (FIAB-NEW-2): from the event's identity, not its position — so re-exporting an
        // evolving agenda keeps each event's UID and a later re-import updates instead of duplicating.
        // Only add the "@filestech" domain when the UID has none, so it never accumulates on round-trips.
        val base = event.uid?.takeIf { it.isNotBlank() } ?: "agenda-tech-$index-${event.startUtcMillis}"
        val uid = if (base.contains("@")) base else "$base@filestech"
        // Audit F2/F4 - the UID is attacker-controlled on any imported event and was the one TEXT
        // value written raw: real newlines in it became content lines, so an exported .ics could
        // carry whole VEVENTs the attacker wrote, under the user's name.
        out.appendContentLine("UID:${IcsLines.escapeText(uid)}")
        out.appendContentLine("DTSTAMP:${utcStamp(nowUtcMillis)}")
        out.appendContentLine(dateProperty("DTSTART", event.startUtcMillis, event))
        out.appendContentLine(dateProperty("DTEND", event.endUtcMillis, event))
        out.appendContentLine("SUMMARY:${IcsLines.escapeText(event.title)}")
        event.description?.takeIf { it.isNotBlank() }?.let { out.appendContentLine("DESCRIPTION:${IcsLines.escapeText(it)}") }
        event.location?.takeIf { it.isNotBlank() }?.let { out.appendContentLine("LOCATION:${IcsLines.escapeText(it)}") }
        event.recurrence?.let { rule ->
            out.appendContentLine("RRULE:${encodeRRule(rule)}")
            if (rule.exDatesUtcMillis.isNotEmpty()) {
                out.appendContentLine("EXDATE:${rule.exDatesUtcMillis.joinToString(",") { utcStamp(it) }}")
            }
        }
        // Written from the enum's own name, never from stored text, so nothing attacker-controlled
        // reaches the line (the concern audit F2/F4 raised about UID).
        if (event.kind != EventKind.NORMAL) {
            out.appendContentLine("$PROP_KIND:${event.kind.name}")
        }
        out.appendContentLine("END:VEVENT")
    }

    /**
     * Audit F3c — the `TZID` parameter is written from the **resolved** zone's id, never from the
     * stored string. The stored string is attacker-reachable (a hand-made `.atbak` carries the field
     * verbatim) and was the one place a value went into the output without passing through either
     * [IcsLines.escapeText] or a validator: a zone id holding a CRLF ended the content line and turned the rest
     * into properties of the attacker's choosing, inside a file the user then shares. A [ZoneId] id
     * cannot contain one. This also removes the last way the emitted offset and the emitted zone name
     * could disagree, since both now come from the same [ZoneId].
     */
    private fun dateProperty(name: String, utcMillis: Long, event: IcsEvent): String {
        val zone = zoneOf(event)
        return when {
            event.allDay -> {
                val date = Instant.ofEpochMilli(utcMillis).atZone(zone).toLocalDate()
                "$name;VALUE=DATE:${date.format(DATE_STAMP)}"
            }
            // Compared on the RULES, not on the id string. An external reviewer pointed out that
            // `zone.id == "UTC"` misses an event stored as `"Z"` — which `ZoneId.of` accepts, which
            // `isCanonical` therefore approves, and which a `.ics` carrying `TZID=Z` produces. Such an
            // event exported as `TZID=Z`: the very unreadable id the fallback below was chosen to
            // avoid, reached from the other direction. `normalized()` folds every fixed-zero-offset
            // spelling — `UTC`, `Z`, `Etc/UTC`, `GMT` — onto the plain `…Z` form every reader knows.
            zone.normalized() == ZoneOffset.UTC -> "$name:${utcStamp(utcMillis)}"
            else -> {
                val local = Instant.ofEpochMilli(utcMillis).atZone(zone).toLocalDateTime()
                "$name;TZID=${IcsLines.quoteParam(zone.id)}:${local.format(LOCAL_STAMP)}"
            }
        }
    }

    private fun encodeRRule(rule: RecurrenceRule): String = buildString {
        append("FREQ=").append(rule.freq.name)
        if (rule.interval > 1) append(";INTERVAL=").append(rule.interval)
        if (rule.freq == RecurrenceFreq.WEEKLY && rule.byWeekdays.isNotEmpty()) {
            append(";BYDAY=").append(rule.byWeekdays.sortedBy { it.isoValue }.joinToString(",") { ISO_TO_BYDAY.getValue(it) })
        }
        rule.count?.let { append(";COUNT=").append(it) }
        rule.untilUtcMillis?.let { append(";UNTIL=").append(utcStamp(it)) }
    }

    private fun utcStamp(utcMillis: Long): String =
        Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).format(UTC_STAMP)

    // --- Decode --------------------------------------------------------------

    /**
     * Audit F8 — properties are collected into a **list per name**, not a single slot.
     *
     * `EXDATE` is one of the few RFC 5545 properties allowed to appear several times in a VEVENT, and
     * every producer that cancels more than a handful of occurrences uses that form rather than one
     * long comma-separated line. Keeping only the last one silently resurrected every occurrence the
     * user had cancelled — including on a round-trip through a file this app had itself imported.
     *
     * For the properties RFC 5545 allows only once, the **first** wins: a malformed file that repeats
     * SUMMARY does not get to have its later copy override the one a reader would show.
     */
    fun decode(text: String, defaultZone: ZoneId): List<IcsEvent> {
        val lines = IcsLines.unfold(text)
        val events = ArrayList<IcsEvent>()
        var current: MutableMap<String, MutableList<IcsProperty>>? = null
        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> current = LinkedHashMap()
                line == "END:VEVENT" -> {
                    current?.let { parseVEvent(it, defaultZone)?.let(events::add) }
                    current = null
                }
                current != null -> {
                    val property = IcsLines.parse(line) ?: continue
                    current.getOrPut(property.name) { ArrayList() } += property
                }
            }
        }
        return events
    }

    private fun Map<String, List<IcsProperty>>.first(name: String): IcsProperty? = this[name]?.firstOrNull()

    private fun parseVEvent(props: Map<String, List<IcsProperty>>, defaultZone: ZoneId): IcsEvent? {
        // SEC-ICS3 — an event with no usable title is dropped (matches the editor's non-blank rule).
        //
        // The FIRST USABLE line, not simply the first: external review pointed out that plain
        // first-wins turns a file whose first SUMMARY is an empty placeholder — concatenated or
        // machine-merged exports do produce those — into a dropped event, where the previous
        // last-wins rule would have imported it. Skipping blanks keeps the tolerance without giving
        // an appended line the power to override a title a reader would already have shown.
        val summary = props["SUMMARY"].orEmpty()
            .firstNotNullOfOrNull { property ->
                sanitizeText(IcsLines.unescapeText(property.value)).takeIf { it.isNotBlank() }
            } ?: return null
        val dtStart = props.first("DTSTART") ?: return null
        val start = parseDateTime(dtStart, defaultZone) ?: return null
        val allDay = dtStart.params["VALUE"] == "DATE"
        // Audit AG-1 — RFC 5545 §3.6.1 : DTEND est FACULTATIF, et DURATION peut le remplacer. Sans
        // l'un ni l'autre, un VEVENT de type DATE dure UN JOUR et un VEVENT horodaté dure zéro.
        //
        // Le repli était `?: start` dans tous les cas, ce qui donnait `end == start`. Or les filtres
        // de recouvrement de l'application sont stricts des deux côtés
        // (`start < fenêtreFin && end > fenêtreDébut`, cf. RecurrenceExpander.singleOccurrenceIfOverlaps
        // et MonthViewModel), et les fenêtres de vue commencent à `atStartOfDay` SUR LE MÊME FUSEAU
        // que celui résolu ici pour une date. `end > débutDuJour` était donc faux à l'égalité exacte :
        // un fichier de jours fériés s'importait, l'écran annonçait « N événements importés », et le
        // jour concerné affichait « Aucun événement ce jour ».
        //
        // Portée exacte, MESURÉE sur S9 le 2026-09-11 par contrôle négatif (le correctif retiré, le
        // même fichier réimporté) : ce sont les filtres PAR JOUR qui cachent l'événement — grille du
        // mois, vue Jour, vue Semaine —, là où la borne de fenêtre coïncide exactement avec son
        // instant. La vue Agenda, elle, l'affichait : elle groupe par date de début sur une fenêtre
        // de ±1 an, que l'égalité ne met pas en défaut. Une première rédaction de ce commentaire
        // disait « nulle part » ; c'était une inférence tirée de la lecture, pas une mesure.
        // Le cas DURATION, lui, s'affichait « 09:00 – 09:00 » — mesuré aussi.
        //
        // Le jumeau faisait déjà bien : DeviceEventMapper force `days.coerceAtLeast(1)` pour une
        // journée entière et lit `DURATION` depuis la colonne du fournisseur. C'est son repli qui est
        // recopié ici — et le parseur de durée, lui, n'est plus recopié du tout (cf. RfcDuration).
        val dtEnd = props.first("DTEND")
        val parsedEnd = dtEnd?.let { parseDateTime(it, defaultZone) }
        val durationMillis = props.first("DURATION")?.value?.let(RfcDuration::parseMillis)?.takeIf { it > 0 }
        val end = when {
            // DTEND n'est retenu que s'il dit quelque chose : un DTEND ANTERIEUR ou EGAL a DTSTART
            // est un export casse, pas une duree. La condition ne le verifiait que pour les journees
            // entieres ; pour un evenement horodate, `maxOf(end, start)` plus bas rabattait la fin
            // sur le debut et produisait la duree nulle que tout ce bloc existe pour eviter.
            // Signale par la relecture gpt-5.2 du 2026-09-11.
            parsedEnd != null && parsedEnd > start -> parsedEnd
            // Une DUREE sur une journee entiere doit rester CALENDAIRE : `P1D` ajoute a un minuit du
            // 30 mars donne 01:00 le 31, la nuit faisant 23 heures. Meme piege que celui traite par
            // `nextMidnightAfter` — il manquait simplement sur cette branche-ci.
            allDay && durationMillis != null && durationMillis % DAY_MILLIS == 0L ->
                midnightAfterDays(start, defaultZone, durationMillis / DAY_MILLIS)
            durationMillis != null -> start + durationMillis
            // Un jour CALENDAIRE, pas 24 h : les nuits de changement d'heure en font 23 ou 25, et
            // ajouter une constante décalerait la borne d'une heure — exactement ce que
            // DeviceEventMapper évite en passant par LocalDate.
            allDay -> nextMidnightAfter(start, defaultZone)
            else -> start + DEFAULT_DURATION_MILLIS
        }
        // Audit F3a/F3b — store the zone the instant was actually computed in, resolved once here and
        // by the same resolver parseDateTime used. Storing the file's raw spelling instead meant an
        // unknown name (every Outlook export names zones the Windows way) was read as the device zone
        // to build the instant, then written to the row as a string nothing downstream could resolve:
        // the expander and the exporter both fell back to UTC, so each export/import round trip moved
        // the event by a whole offset and every recurring occurrence drifted at the DST boundary.
        val zoneId = when {
            allDay -> defaultZone.id
            dtStart.value.trim().endsWith("Z") -> "UTC"
            else -> TimeZones.normalize(dtStart.params["TZID"], defaultZone)
        }
        val recurrence = props.first("RRULE")
            ?.let { parseRRule(it.value, props["EXDATE"].orEmpty(), defaultZone) }
        return IcsEvent(
            title = summary,
            description = props.first("DESCRIPTION")?.value?.let(IcsLines::unescapeText)?.let(::sanitizeText),
            location = props.first("LOCATION")?.value?.let(IcsLines::unescapeText)?.let(::sanitizeText),
            startUtcMillis = start,
            endUtcMillis = maxOf(end, start),
            timeZoneId = zoneId,
            allDay = allDay,
            recurrence = recurrence,
            // Audit F2/F4 - UID bypassed the sanitiser every other imported string goes through.
            // Line breaks and control characters are dropped outright: never legitimate in a UID,
            // and they are the injection primitive.
            kind = props.first(PROP_KIND)?.value?.trim()?.uppercase()
                ?.let { name -> EventKind.entries.firstOrNull { it.name == name } }
                ?: EventKind.NORMAL,
            uid = props.first("UID")?.value?.let(IcsLines::unescapeText)
                ?.filterNot { it == '\n' || it == '\r' || it.isISOControl() }
                ?.let(::sanitizeText)
                ?.takeIf { it.isNotBlank() },
        )
    }

    private fun parseDateTime(property: IcsProperty, defaultZone: ZoneId): Long? = runCatching {
        val raw = property.value.trim()
        when {
            property.params["VALUE"] == "DATE" || (raw.length == 8 && !raw.contains('T')) ->
                LocalDate.parse(raw, DATE_STAMP).atStartOfDay(defaultZone).toInstant().toEpochMilli()
            raw.endsWith("Z") ->
                LocalDateTime.parse(raw.dropLast(1), LOCAL_STAMP).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
            else -> {
                val zone = TimeZones.resolve(property.params["TZID"], defaultZone)
                LocalDateTime.parse(raw, LOCAL_STAMP).atZone(zone).toInstant().toEpochMilli()
            }
        }
    }.getOrNull()

    private fun parseRRule(
        value: String,
        exDateLines: List<IcsProperty>,
        defaultZone: ZoneId,
    ): RecurrenceRule? = runCatching {
        val parts = value.split(";").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0].uppercase() to kv[1] else null
        }.toMap()
        val freq = RecurrenceFreq.entries.firstOrNull { it.name == parts["FREQ"]?.uppercase() } ?: return null
        // Each EXDATE line carries its own parameters, so the lines are parsed separately rather than
        // concatenated: two lines may legitimately name different TZIDs, and a single merged parameter
        // set would read one of them in the other's zone. Duplicates are dropped — the same instant
        // excluded twice is the same exclusion.
        val exDates = exDateLines.flatMap { line ->
            line.value.split(",").mapNotNull { token ->
                parseDateTime(IcsProperty("EXDATE", line.params, token.trim()), defaultZone)
            }
        }.distinct()
        // COUNT below 1 is not a bound, and COUNT together with UNTIL is not a valid RRULE — both
        // violate an invariant of RecurrenceRule, and since this whole function is wrapped in
        // runCatching, throwing would silently strip the recurrence from an otherwise fine event.
        // Drop the bad bound instead, and let COUNT win over UNTIL exactly as the device importer
        // does (DeviceEventMapper), so the same file reads the same way through either path.
        val count = parts["COUNT"]?.toIntOrNull()?.takeIf { it >= 1 }
        val until = if (count == null) {
            parts["UNTIL"]?.let { parseDateTime(IcsProperty("UNTIL", emptyMap(), it), defaultZone) }
        } else {
            null
        }
        RecurrenceRule(
            freq = freq,
            interval = parts["INTERVAL"]?.toIntOrNull()
                ?.coerceIn(1, RecurrenceRule.MAX_INTERVAL) ?: 1,
            byWeekdays = parts["BYDAY"]?.split(",")?.mapNotNull { BYDAY_TO_ISO[it.trim().uppercase()] }?.toSet().orEmpty(),
            count = count,
            untilUtcMillis = until,
            // The EXDATEs were parsed just above and then dropped on the floor: every cancelled
            // occurrence came back to life on import, including on a round-trip through our own
            // export, which does write them. detekt had been reporting the symptom as an unused
            // `exDates` property.
            exDatesUtcMillis = exDates,
        )
    }.getOrNull()

    /**
     * SEC-ICS2 — strip Unicode bidirectional-control characters from imported free text and cap its
     * length. An imported `.ics` is untrusted; without the strip an RLO/LRO override could spoof how
     * a title reads on screen/in the widget, and without the cap a single multi-MB folded field
     * could bloat the DB (same guard as the device-calendar import).
     */
    private fun sanitizeText(text: String): String = BidiSanitizer.stripAndCap(text)

    /**
     * The zone an event is exported in. The fallback is `ZoneId.of("UTC")` and not [ZoneOffset.UTC]
     * even though they denote the same instant: their ids differ (`UTC` vs `Z`), and [dateProperty]
     * branches on that id to emit the plain `…Z` form. With the offset's `Z` id the branch missed and
     * an unresolvable zone was exported as `TZID=Z`, which no reader understands.
     */
    private fun zoneOf(event: IcsEvent): ZoneId = TimeZones.resolve(event.timeZoneId, ZoneId.of("UTC"))
}
