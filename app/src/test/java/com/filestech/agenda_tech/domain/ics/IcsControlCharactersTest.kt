package com.filestech.agenda_tech.domain.ics

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Control characters in a TEXT value of an exported `.ics` (security audit, 2026-10-13).
 *
 * RFC 5545 allows none but the tab. U+000B, U+000C and U+001C to U+001E end a line for Python's
 * `str.splitlines`, so a reader built on it would split a title from an imported file into a VEVENT the
 * user never wrote — in a file the user shares under their own name.
 */
class IcsControlCharactersTest {

    private val now = 1_700_000_000_000L
    private val paris = ZoneId.of("Europe/Paris")

    private fun event(title: String, description: String? = null, location: String? = null) = IcsEvent(
        title = title,
        description = description,
        location = location,
        startUtcMillis = LocalDateTime.of(2025, 6, 1, 9, 0).atZone(paris).toInstant().toEpochMilli(),
        endUtcMillis = LocalDateTime.of(2025, 6, 1, 10, 0).atZone(paris).toInstant().toEpochMilli(),
        timeZoneId = paris.id,
        allDay = false,
        recurrence = null,
    )

    /** The SUMMARY content line, unfolded. */
    private fun summary(ics: String): String =
        IcsLines.unfold(ics).single { it.startsWith("SUMMARY:") }

    @ParameterizedTest
    @ValueSource(ints = [0x00, 0x07, 0x0B, 0x0C, 0x1C, 0x1D, 0x1E, 0x1F, 0x7F, 0x9B])
    fun `a control character from an imported text never reaches the exported file`(code: Int) {
        val control = Char(code)

        val exported = IcsCodec.encode(listOf(event("Réunion${control}END:VEVENT", "a${control}b", "Salle${control}2")), now)

        assertThat(exported.none { it.isISOControl() && it != CR && it != LF }).isTrue()
        assertThat(summary(exported)).isEqualTo("SUMMARY:Réunion END:VEVENT")
    }

    @Test
    fun `a tab is kept, the one control character a TEXT value allows`() {
        assertThat(summary(IcsCodec.encode(listOf(event("A${TAB}B")), now))).isEqualTo("SUMMARY:A${TAB}B")
    }

    private companion object {
        val TAB = Char(9)
        val LF = Char(10)
        val CR = Char(13)
    }
}
