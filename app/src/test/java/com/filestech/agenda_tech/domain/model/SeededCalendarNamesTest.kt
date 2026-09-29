package com.filestech.agenda_tech.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The first-run calendar is created under `default_calendar_name` in the language of the first run,
 * and that name is then stored for good. [Calendar.SEEDED_NAMES] is how the UI recognises it later to
 * show it in the current language. A language added without its name in that set would leave its
 * users with the calendar stuck in whatever language they first opened the app in - so the set is
 * checked against the resources themselves, not against a copy of them.
 */
class SeededCalendarNamesTest {

    @Test
    fun `the seeded names are exactly the default calendar name of every shipped language`() {
        assertThat(Calendar.SEEDED_NAMES).isEqualTo(defaultNamesInResources().values.toSet())
    }

    /** Negative control: a scan that found no file would compare two empty sets and pass. */
    @Test
    fun `the scan actually reads every language`() {
        val names = defaultNamesInResources()
        assertThat(names.keys).containsAtLeast("values", "values-fr", "values-de", "values-it", "values-es")
        assertThat(names["values"]).isEqualTo("Personal")
        assertThat(names["values-fr"]).isEqualTo("Perso")
    }

    @Test
    fun `the first-run calendar is recognised under each of its names`() {
        Calendar.SEEDED_NAMES.forEach { seeded ->
            assertThat(Calendar(name = seeded).hasSeededName).isTrue()
        }
    }

    @Test
    fun `an imported calendar keeps its own name even when it is Personal`() {
        assertThat(Calendar(name = "Personal", sourceId = "device:6").hasSeededName).isFalse()
    }

    @Test
    fun `a calendar the user named is never renamed`() {
        assertThat(Calendar(name = "Travail").hasSeededName).isFalse()
        assertThat(Calendar(name = "perso").hasSeededName).isFalse()
        assertThat(Calendar(name = "Perso ").hasSeededName).isFalse()
    }

    /** `default_calendar_name` per values folder, read from the XML the build packages. */
    private fun defaultNamesInResources(): Map<String, String> {
        val res = File(RES_ROOT)
        check(res.isDirectory) {
            "Resource root introuvable depuis ${File(".").absolutePath} - le test ne mesurerait rien."
        }
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        return res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty()
            .mapNotNull { dir ->
                val strings = File(dir, "strings.xml").takeIf { it.isFile } ?: return@mapNotNull null
                val nodes = parser.parse(strings).getElementsByTagName("string")
                (0 until nodes.length)
                    .map { nodes.item(it) }
                    .firstOrNull { it.attributes.getNamedItem("name").nodeValue == KEY }
                    // Android unescapes \' at build time; the stored name is the unescaped one.
                    ?.let { dir.name to it.textContent.replace("\\'", "'") }
            }
            .toMap()
    }

    private companion object {
        const val RES_ROOT = "src/main/res"
        const val KEY = "default_calendar_name"
    }
}
