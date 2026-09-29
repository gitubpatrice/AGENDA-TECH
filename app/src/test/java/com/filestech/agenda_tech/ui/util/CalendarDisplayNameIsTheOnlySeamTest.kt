package com.filestech.agenda_tech.ui.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **`Calendar.displayName()` is the only seam** through which the UI shows a calendar's name.
 *
 * Six places show one (calendar list, its edit and delete dialogs, the editor's field and menu,
 * search results). A seventh that read `calendar.name` directly would compile, look right in the
 * language the app was installed in, and show "Perso" on a German screen. Like
 * `AppResultLauncherIsTheOnlySeamTest`, this checks the shape of the source, because the defect is a
 * copy that behaves correctly on its own.
 *
 * Limit, stated: it recognises the receiver names this code base uses for a calendar. A calendar held
 * in a variable named otherwise would slip through; the patterns are the ones every existing site used.
 */
class CalendarDisplayNameIsTheOnlySeamTest {

    @Test
    fun `no screen reads a calendar name without going through displayName`() {
        val offenders = uiSources()
            .filter { it.name != SEAM }
            .flatMap { source ->
                source.readLines().mapIndexedNotNull { index, line ->
                    "${source.name}:${index + 1}".takeIf { RAW_READ.containsMatchIn(line) }
                }
            }

        assertThat(offenders).isEmpty()
    }

    /** Negative control: the pattern must catch each form the six sites had before the seam. */
    @Test
    fun `the pattern recognises the raw reads it replaced`() {
        listOf(
            "Text(calendar.name, style = x)",
            "stringResource(R.string.calendar_delete_confirm_body, target.name)",
            "mutableStateOf(initial?.name.orEmpty())",
            "calendars.firstOrNull { it.id == selectedId }?.name.orEmpty()",
            "\"\${hit.calendar.name} · hidden\"",
        ).forEach { assertThat(RAW_READ.containsMatchIn(it)).isTrue() }

        assertThat(RAW_READ.containsMatchIn("Text(calendar.displayName())")).isFalse()
    }

    /** Negative control: a scan that read nothing would also find no offender. */
    @Test
    fun `the scan actually reads the screens`() {
        val sources = uiSources()
        assertThat(sources.size).isGreaterThan(MIN_EXPECTED_SOURCES)
        assertThat(sources.map { it.name }).containsAtLeast(SEAM, "CalendarsScreen.kt", "SearchScreen.kt")
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
        const val SEAM = "CalendarDisplayName.kt"
        const val MIN_EXPECTED_SOURCES = 20
        val RAW_READ = Regex("""\b(?:calendar|target|initial|cal)\??\.name\b|\}\?\.name\b""")
    }
}
