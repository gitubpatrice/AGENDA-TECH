package com.filestech.agenda_tech.core.text

import java.text.Normalizer
import java.util.Locale

/**
 * Folds text so a search matches what a person means rather than what they typed exactly.
 *
 * Someone looking for a past appointment types `reunion`, not `Réunion` — on a phone keyboard, in a
 * hurry. Matching raw strings would answer "no results" for an event they are looking straight at.
 *
 * Folding is: Unicode decomposition (NFD) → drop the combining marks → lowercase. `é` decomposes to
 * `e` + a combining acute, so removing the marks leaves `e`. Ligatures are expanded first because
 * they are single characters, not a base plus an accent, so NFD leaves them untouched — `cœur` would
 * otherwise never match `coeur`.
 *
 * [Locale.ROOT] is deliberate: with the device in Turkish, `Locale.getDefault()` would lowercase `I`
 * to a dotless `ı` and a French agenda would stop matching its own entries.
 */
object SearchText {

    /** Combining marks left behind by NFD (the accents themselves). */
    private val COMBINING_MARKS = "\\p{Mn}+".toRegex()

    fun fold(raw: String): String = raw
        .expandLigatures()
        .let { Normalizer.normalize(it, Normalizer.Form.NFD) }
        .replace(COMBINING_MARKS, "")
        .lowercase(Locale.ROOT)

    /** True when [haystack] contains [needle], both folded. An empty needle matches nothing. */
    fun matches(haystack: String, foldedNeedle: String): Boolean =
        foldedNeedle.isNotEmpty() && fold(haystack).contains(foldedNeedle)

    private fun String.expandLigatures(): String {
        // Cheap guard: the vast majority of strings contain none of these, and scanning beats
        // allocating five replacement copies per field on every keystroke.
        if (none { it in LIGATURES }) return this
        return buildString(length + 2) {
            this@expandLigatures.forEach { c -> append(LIGATURE_EXPANSIONS[c] ?: c) }
        }
    }

    private val LIGATURE_EXPANSIONS = mapOf(
        'œ' to "oe", 'Œ' to "oe",
        'æ' to "ae", 'Æ' to "ae",
        // Not a ligature, but the same problem: NFD leaves it alone and it has no lowercase pair.
        'ß' to "ss",
        // Typographic ligatures. Never typed on a phone, but the app ingests foreign text — an .ics
        // exported from a publishing tool can carry them, and "ﬁche" would then never match "fiche".
        'ﬁ' to "fi", 'ﬂ' to "fl", 'ﬃ' to "ffi", 'ﬄ' to "ffl", 'ﬅ' to "st",
    )
    private val LIGATURES = LIGATURE_EXPANSIONS.keys
}
