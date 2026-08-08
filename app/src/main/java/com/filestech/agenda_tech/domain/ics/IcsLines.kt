package com.filestech.agenda_tech.domain.ics

/** One parsed content line: property name, its parameters, and its raw (still escaped) value. */
internal data class IcsProperty(val name: String, val params: Map<String, String>, val value: String)

/**
 * The **line syntax** of RFC 5545, with no notion of what a calendar is: folding, unfolding, TEXT
 * escaping, parameter quoting, and splitting a content line into name / parameters / value.
 *
 * Separated from [IcsCodec], which owns the calendar semantics (what a VEVENT is, how an RRULE maps
 * to a [com.filestech.agenda_tech.domain.model.RecurrenceRule], which time zone an instant belongs
 * to). The split is not cosmetic: nearly every defect this audit found in the `.ics` path was a
 * *syntax* defect — a fold that cut a character in half, an escape that missed carriage returns, a
 * value split that ignored quoting — and each was buried among the calendar logic where it read as
 * incidental. Here they are the subject.
 *
 * Everything is `internal`: this is the codec's own vocabulary, not an API.
 */
internal object IcsLines {

    const val CRLF = "\r\n"

    /**
     * RFC 5545 §3.1 bounds a content line to 75 **octets** excluding the line break, and the space
     * that opens a continuation line counts toward its own 75. 73 leaves room for that space and a
     * margin, and it is what this codec has always emitted for ASCII — the unit here changed from
     * characters to octets (audit F14), not the width.
     */
    const val FOLD_LIMIT_OCTETS = 73

    /**
     * Fold a content line to ≤ [FOLD_LIMIT_OCTETS] octets, continuation lines prefixed with a space.
     *
     * Audit F14 — this counted Kotlin `Char`s, which are UTF-16 code *units*. An emoji is two of them
     * (a surrogate pair), so a fold landing between the two split the pair; the halves are not
     * characters, and `toByteArray(UTF_8)` maps an unpaired surrogate to `?`. An emoji at the wrong
     * offset in a title therefore came out of the exporter as `??` — silent, and unrecoverable from
     * the file. Counting octets and stepping by whole code points is also what RFC 5545 asks for: it
     * bounds the line in octets and forbids folding inside a multi-octet character.
     */
    fun fold(line: String): String {
        val builder = StringBuilder(line.length + line.length / FOLD_LIMIT_OCTETS + 1)
        var octets = 0
        var index = 0
        while (index < line.length) {
            val codePoint = line.codePointAt(index)
            val chars = Character.charCount(codePoint)
            val width = utf8Length(codePoint)
            if (octets > 0 && octets + width > FOLD_LIMIT_OCTETS) {
                builder.append(CRLF).append(' ')
                octets = 0
            }
            builder.append(line, index, index + chars)
            octets += width
            index += chars
        }
        return builder.toString()
    }

    /** Octets [codePoint] occupies in UTF-8. */
    private fun utf8Length(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

    /**
     * Join RFC 5545 folded lines (a following line starting with space/tab continues the previous).
     *
     * SEC-ICS1 — accumulates each logical line in a [StringBuilder] rather than repeatedly
     * concatenating immutable strings, so a maliciously deep fold stays O(n) instead of O(n²).
     */
    fun unfold(text: String): List<String> {
        val rawLines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val result = ArrayList<String>()
        var current: StringBuilder? = null
        for (raw in rawLines) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && current != null) {
                current.append(raw, 1, raw.length)
            } else {
                current?.let { result += it.toString() }
                current = if (raw.isNotEmpty()) StringBuilder(raw) else null
            }
        }
        current?.let { result += it.toString() }
        return result
    }

    /**
     * RFC 5545 TEXT escaping.
     *
     * Audit F9/F10/F11 - a carriage return has to be neutralised too. Escaping only LF left a bare
     * CR in the output, which every unfolder treats as a line break: a CR in a title (pasted text,
     * a device-imported event) silently split the content line and turned the remainder into a
     * forged property. CRLF is collapsed first so a real line break becomes one escape, not two.
     */
    fun escapeText(text: String): String =
        text.replace("\\", "\\\\")
            .replace("\r\n", "\\n")
            .replace("\r", "\\n")
            .replace("\n", "\\n")
            .replace(",", "\\,")
            .replace(";", "\\;")

    fun unescapeText(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    'n', 'N' -> out.append('\n')
                    ',' -> out.append(',')
                    ';' -> out.append(';')
                    '\\' -> out.append('\\')
                    else -> out.append(text[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /**
     * Wraps a parameter value in DQUOTE when RFC 5545 §3.2 requires it — the value contains `:`, `;`
     * or `,`.
     *
     * Found by external review of the `.ics` lot. A zone id can be a fixed offset (`+02:00`), which
     * `TimeZones` accepts on purpose and which a `.ics` may legitimately carry. Written unquoted, it
     * produced `DTSTART;TZID=+02:00:20260808T100000` — and [parse] split on the FIRST colon, so it
     * read the parameter as `TZID=+02` and the value as `00:20260808T100000`, which parses as
     * nothing. The event was silently dropped, by our own reader, from our own export.
     */
    fun quoteParam(value: String): String =
        if (value.any { it == ':' || it == ';' || it == ',' }) "\"$value\"" else value

    /** Split one unfolded content line into its name, parameters and raw value; null if malformed. */
    fun parse(line: String): IcsProperty? {
        val colon = valueSeparatorIndex(line)
        if (colon <= 0) return null
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)
        // Split on unquoted `;` for the same reason the colon is found the same way: quoting is what
        // lets a parameter value hold a delimiter, so a reader that honours quotes for one delimiter
        // and not the other is only half a reader. Concretely, splitting on every `;` lets a quoted
        // value be cut into extra parameters that the reader then obeys — a `VALUE=DATE` smuggled
        // inside an unrelated `X-` parameter makes a timed event parse as a date and be dropped.
        val headParts = splitUnquoted(head, ';')
        val name = headParts.first().uppercase()
        // RFC 5545 §3.2 lets a parameter value be quoted, and a producer may quote it even when it
        // need not. Unquoting here rather than at each reader keeps `TZID="Europe/Paris"` and
        // `TZID=Europe/Paris` the same parameter, which is what every reader downstream assumes.
        val params = headParts.drop(1).mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0].uppercase() to kv[1].removeSurrounding("\"") else null
        }.toMap()
        return IcsProperty(name, params, value)
    }

    /**
     * Index of the colon that separates a content line's name+parameters from its value, or -1.
     *
     * A colon inside a **quoted** parameter value is not that separator — RFC 5545 §3.2 requires the
     * quotes precisely so that a value may contain one, and a fixed-offset `TZID="+02:00"` is the
     * everyday case.
     */
    private fun valueSeparatorIndex(line: String): Int {
        var inQuotes = false
        line.forEachIndexed { index, c ->
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ':' && !inQuotes -> return index
            }
        }
        return -1
    }

    /** Splits [text] on [delimiter], ignoring delimiters inside a DQUOTE-ed parameter value. */
    private fun splitUnquoted(text: String, delimiter: Char): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (c in text) {
            when {
                c == '"' -> { inQuotes = !inQuotes; current.append(c) }
                c == delimiter && !inQuotes -> { parts += current.toString(); current.clear() }
                else -> current.append(c)
            }
        }
        parts += current.toString()
        return parts
    }
}
