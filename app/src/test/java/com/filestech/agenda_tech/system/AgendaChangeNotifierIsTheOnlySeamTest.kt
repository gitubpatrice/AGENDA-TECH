package com.filestech.agenda_tech.system

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **`AgendaChangeNotifier` est la seule couture** : personne d'autre ne redessine le widget à la
 * main.
 *
 * ## Pourquoi un test sur le source, et pas sur le comportement
 *
 * Le défaut visé n'est pas une valeur fausse, c'est une **copie**. Il ne se manifeste jamais au
 * moment où on l'écrit : les deux copies marchent. Il se manifeste des semaines plus tard, quand
 * l'une est durcie et l'autre pas — et alors aucun test de comportement ne le voit, puisque chaque
 * copie, prise seule, fait ce qu'on attend d'elle.
 *
 * C'est le motif dominant de l'audit du 2026-09-11, relevé **cinq fois** en une journée :
 *
 * 1. Sept fonctions jumelles trouvées par l'audit initial, une seule des deux durcie à chaque fois.
 * 2. Mon correctif d'import : la pluralité manquait côté base, pas côté fichier.
 * 3. Mon correctif AG-9 : un commentaire décrivant le défaut, et à côté un appel qui ne le traitait
 *    pas.
 * 4. C1 et C2 : la consolidation par [AgendaChangeNotifier] n'avait été appliquée à **aucun** des
 *    deux sites que sa propre KDoc cite nommément.
 * 5. Le notifier lui-même : `runCatching` y avalait `CancellationException` alors que la copie qu'il
 *    remplaçait la relançait. La copie centrale était la plus faible des deux.
 *
 * Dans les cinq cas, **aucun test ne couvrait le chemin**. Celui-ci couvre la forme plutôt que le
 * comportement, parce que c'est la forme qui est en cause.
 *
 * ## Ce qu'il autorise
 *
 * [AgendaChangeNotifier] appelle `updateAll`, évidemment. `AgendaWidget` et son receiver sont le
 * widget lui-même. Tout le reste doit passer par la couture — y compris un futur écran qui n'existe
 * pas encore, et c'est là tout l'intérêt.
 */
class AgendaChangeNotifierIsTheOnlySeamTest {

    @Test
    fun `nothing outside the seam redraws the widget by hand`() {
        val offenders = kotlinSources()
            .filter { it.name !in ALLOWED }
            .filter { it.readText().contains("updateAll(") }
            .map { it.name }
            .sorted()

        assertThat(offenders).isEmpty()
    }

    /**
     * Le contrôle négatif de tout ce fichier, et il n'est pas décoratif.
     *
     * Un test qui balaie une arborescence rend « aucun coupable » aussi bien quand tout est propre
     * que quand il n'a **rien lu du tout** — un chemin relatif faux, un répertoire de travail qui
     * change, et il devient vert pour toujours. C'est la forme la plus traître du test sur chemin
     * mort. Les deux bornes ci-dessous échouent dans ce cas.
     */
    @Test
    fun `the scan actually reads the sources`() {
        val sources = kotlinSources()
        assertThat(sources.size).isGreaterThan(MIN_EXPECTED_SOURCES)
        // Et il voit bien ce qu'il est censé voir : la couture, elle, appelle `updateAll`.
        assertThat(sources.filter { it.name == "AgendaChangeNotifier.kt" }).hasSize(1)
        assertThat(
            sources.first { it.name == "AgendaChangeNotifier.kt" }.readText(),
        ).contains("updateAll(")
    }

    private fun kotlinSources(): List<File> {
        val root = File(SOURCE_ROOT)
        check(root.isDirectory) {
            "Source root introuvable depuis ${File(".").absolutePath} — le test ne mesurerait rien."
        }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private companion object {
        /** Relatif au répertoire du module, qui est le répertoire de travail des tests Gradle. */
        const val SOURCE_ROOT = "src/main/java/com/filestech/agenda_tech"

        /** Bien en dessous du compte réel : ce seuil détecte un balayage vide, pas une dérive. */
        const val MIN_EXPECTED_SOURCES = 50

        val ALLOWED = setOf(
            "AgendaChangeNotifier.kt",
            "AgendaWidget.kt",
            "AgendaWidgetReceiver.kt",
        )
    }
}
