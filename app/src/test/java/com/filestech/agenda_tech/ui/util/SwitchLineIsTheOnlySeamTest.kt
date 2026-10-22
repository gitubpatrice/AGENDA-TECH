package com.filestech.agenda_tech.ui.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **`SwitchLine` is the only seam** through which a screen draws a line with a switch.
 *
 * Three lines drew their own (the editor's "All day", the automatic backup, the settings), and two of them
 * toggled from the switch alone: a tap on the label did nothing (seen on the S9, 2026-10-13). The third
 * toggled, but TalkBack read it twice. A fourth line written the same way would compile and look right,
 * so, like `CalendarDisplayNameIsTheOnlySeamTest`, this checks the shape of the source.
 *
 * One switch is let through, by name and by count: the calendars' list, where the line edits the calendar
 * and the switch shows or hides its events — two actions that must stay two targets.
 */
class SwitchLineIsTheOnlySeamTest {

    @Test
    fun `no screen draws a switch outside SwitchLine`() {
        val offenders = uiSources()
            .filter { it.name != SEAM && it.name !in ALLOWED }
            .flatMap { source ->
                source.readLines().mapIndexedNotNull { index, line ->
                    "${source.name}:${index + 1}".takeIf { SWITCH_CALL.containsMatchIn(line) }
                }
            }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the calendars list keeps its one switch, and no more`() {
        ALLOWED.forEach { (name, count) ->
            val source = uiSources().single { it.name == name }
            assertThat(source.readLines().count { SWITCH_CALL.containsMatchIn(it) }).isEqualTo(count)
        }
    }

    /** Negative control: the pattern must catch the forms the three lines had before the seam. */
    @Test
    fun `the pattern recognises the switches it replaced`() {
        listOf(
            "Switch(checked = state.allDay, onCheckedChange = onAllDayChange)",
            "        Switch(checked = checked, onCheckedChange = onCheckedChange)",
            "            Switch(",
            "androidx.compose.material3.Switch(checked = on, onCheckedChange = null)",
        ).forEach { assertThat(SWITCH_CALL.containsMatchIn(it)).isTrue() }

        listOf(
            "SwitchLine(checked = checked, onCheckedChange = onCheckedChange)",
            "private fun SwitchRow(",
            ".toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)",
            "import androidx.compose.material3.Switch",
        ).forEach { assertThat(SWITCH_CALL.containsMatchIn(it)).isFalse() }
    }

    /** Negative control: a scan that read nothing would also find no offender. */
    @Test
    fun `the scan actually reads the screens`() {
        val sources = uiSources()
        assertThat(sources.size).isGreaterThan(MIN_EXPECTED_SOURCES)
        assertThat(sources.map { it.name })
            .containsAtLeast(SEAM, "EventEditorScreen.kt", "BackupScreen.kt", "SettingsScreen.kt", "CalendarsScreen.kt")
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
        const val SEAM = "SwitchLine.kt"
        const val MIN_EXPECTED_SOURCES = 20
        val ALLOWED = mapOf("CalendarsScreen.kt" to 1)
        val SWITCH_CALL = Regex("""(?<![\w.])Switch\(|material3\.Switch\(""")
    }
}
