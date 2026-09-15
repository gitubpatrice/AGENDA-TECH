package com.filestech.agenda_tech.ui.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **`rememberAppResultLauncher` is the only seam** through which the app opens an activity for a result.
 *
 * A launcher written with the platform API still works — its picker opens and returns — but the app
 * locks behind it, and the flow that was waiting for the result is lost after the PIN. That is the
 * defect fixed on 2026-09-15 across eight launchers in five screens. Like
 * `AgendaChangeNotifierIsTheOnlySeamTest`, this checks the shape of the source, because the defect is
 * a copy that behaves correctly on its own.
 */
class AppResultLauncherIsTheOnlySeamTest {

    @Test
    fun `nothing outside the seam registers an activity result launcher by hand`() {
        val offenders = kotlinSources()
            .filter { it.name != SEAM }
            .filter { source -> FORBIDDEN.any { source.readText().contains(it) } }
            .map { it.name }
            .sorted()

        assertThat(offenders).isEmpty()
    }

    /** The negative control: a scan that read nothing would also find no offender. */
    @Test
    fun `the scan actually reads the sources`() {
        val sources = kotlinSources()
        assertThat(sources.size).isGreaterThan(MIN_EXPECTED_SOURCES)
        val seam = sources.filter { it.name == SEAM }
        assertThat(seam).hasSize(1)
        assertThat(seam.single().readText()).contains("rememberLauncherForActivityResult(")
    }

    private fun kotlinSources(): List<File> {
        val root = File(SOURCE_ROOT)
        check(root.isDirectory) {
            "Source root introuvable depuis ${File(".").absolutePath} — le test ne mesurerait rien."
        }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private companion object {
        const val SOURCE_ROOT = "src/main/java/com/filestech/agenda_tech"
        const val MIN_EXPECTED_SOURCES = 50
        const val SEAM = "AppResultLauncher.kt"
        val FORBIDDEN = listOf("rememberLauncherForActivityResult(", "registerForActivityResult(")
    }
}
