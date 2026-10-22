package com.filestech.agenda_tech.ui.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **`ColorSwatches` is the only seam** through which a screen offers the calendar colours.
 *
 * Three screens drew their own swatches (editor, settings, a calendar's dialog) and had drifted apart:
 * the settings' row did not scroll, which hid two colours on the S9, and none of the three named its
 * swatches to TalkBack (2026-10-22). Every copy began by walking `CalendarColor.entries`, so this
 * checks the shape of the source, like `CalendarDisplayNameIsTheOnlySeamTest`.
 */
class ColorSwatchesIsTheOnlySeamTest {

    @Test
    fun `no screen walks the calendar colours outside ColorSwatches`() {
        val offenders = uiSources()
            .filter { it.name != SEAM }
            .flatMap { source ->
                source.readLines().mapIndexedNotNull { index, line ->
                    "${source.name}:${index + 1}".takeIf { COLOURS_WALKED.containsMatchIn(line) }
                }
            }

        assertThat(offenders).isEmpty()
    }

    /** Negative control: the pattern must catch the forms the three copies had. */
    @Test
    fun `the pattern recognises the copies it replaced`() {
        listOf(
            "            CalendarColor.entries.forEach { color ->",
            "                    CalendarColor.entries.forEach { swatch ->",
            "CalendarColor.entries.map { it.argb }",
        ).forEach { assertThat(COLOURS_WALKED.containsMatchIn(it)).isTrue() }

        assertThat(COLOURS_WALKED.containsMatchIn("ColorSwatches(selected = color, onSelect = onSelect)")).isFalse()
    }

    /** Negative control: a scan that read nothing would also find no offender. */
    @Test
    fun `the scan actually reads the screens`() {
        val sources = uiSources()
        assertThat(sources.size).isGreaterThan(MIN_EXPECTED_SOURCES)
        assertThat(sources.map { it.name })
            .containsAtLeast(SEAM, "EventEditorScreen.kt", "SettingsScreen.kt", "CalendarsScreen.kt")
    }

    private fun uiSources(): List<File> {
        val root = File(UI_ROOT)
        check(root.isDirectory) {
            "Source root introuvable depuis ${File(".").absolutePath} - le test ne mesurerait rien."
        }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private companion object {
        const val UI_ROOT = "src/main/java/com/filestech/agenda_tech/ui"
        const val SEAM = "ColorSwatches.kt"
        const val MIN_EXPECTED_SOURCES = 20
        val COLOURS_WALKED = Regex("""\bCalendarColor\.entries\b""")
    }
}
