package com.filestech.agenda_tech.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

class SearchTextTest {

    @Test
    fun `folds accents so a keyboard-typed query finds the real entry`() {
        assertThat(SearchText.fold("Réunion")).isEqualTo("reunion")
        assertThat(SearchText.fold("Dentiste à Avignon")).isEqualTo("dentiste a avignon")
        assertThat(SearchText.fold("ÉCOLE")).isEqualTo("ecole")
        assertThat(SearchText.fold("Noël")).isEqualTo("noel")
        assertThat(SearchText.fold("crème brûlée")).isEqualTo("creme brulee")
        assertThat(SearchText.fold("garçon")).isEqualTo("garcon")
    }

    @Test
    fun `expands ligatures — NFD alone would leave them unmatched`() {
        assertThat(SearchText.fold("cœur")).isEqualTo("coeur")
        assertThat(SearchText.fold("Œuvre")).isEqualTo("oeuvre")
        assertThat(SearchText.fold("nævus")).isEqualTo("naevus")
        assertThat(SearchText.fold("Straße")).isEqualTo("strasse")
    }

    @Test
    fun `folds text already composed the other way - NFD vs NFC must agree`() {
        // "e-acute" can arrive as one code point (NFC) or as "e" + a combining acute (NFD); an
        // .ics file or a device calendar can carry either, and both must fold to the same thing.
        // Written with explicit escapes rather than literal accented characters: a formatter that
        // normalised this file would silently make the two sides identical and the test vacuous.
        val precomposed = "caf\u00E9"
        val decomposed = "cafe\u0301"

        assertThat(precomposed).isNotEqualTo(decomposed) // the premise this test rests on
        assertThat(SearchText.fold(precomposed)).isEqualTo(SearchText.fold(decomposed))
        assertThat(SearchText.fold(decomposed)).isEqualTo("cafe")
    }

    @Test
    fun `lowercases with ROOT — a Turkish device must not break a French agenda`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            // With the default locale, "I" would lowercase to the dotless "ı" and stop matching.
            assertThat(SearchText.fold("INVITATION")).isEqualTo("invitation")
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `leaves plain text and digits alone`() {
        assertThat(SearchText.fold("meeting 14h30")).isEqualTo("meeting 14h30")
        assertThat(SearchText.fold("")).isEqualTo("")
    }

    @Test
    fun `matches is accent- and case-insensitive`() {
        assertThat(SearchText.matches("Réunion d'équipe", SearchText.fold("reunion"))).isTrue()
        assertThat(SearchText.matches("Réunion d'équipe", SearchText.fold("ÉQUIPE"))).isTrue()
        assertThat(SearchText.matches("Réunion d'équipe", SearchText.fold("dentiste"))).isFalse()
    }

    @Test
    fun `an empty query matches nothing rather than everything`() {
        // Guards the search screen's "show nothing until asked" behaviour at the source: a
        // contains("") would otherwise report every event as a hit.
        assertThat(SearchText.matches("Réunion", "")).isFalse()
    }
}
