package com.filestech.agenda_tech.ui.screens.settings

import com.filestech.agenda_tech.domain.repository.LockRepository
import com.filestech.agenda_tech.domain.usecase.FakeSettingsRepository
import com.filestech.agenda_tech.security.AppLockManager
import com.filestech.agenda_tech.security.BiometricGate
import com.filestech.agenda_tech.system.AgendaChangeNotifier
import com.filestech.agenda_tech.system.notifications.ReminderNotifier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * **La garantie LOCK-3 : « activer le verrou ne laisse jamais les titres lisibles sur l'écran
 * d'accueil ».**
 *
 * ## Pourquoi ce fichier existe
 *
 * Le widget vit hors du verrou de l'application. [com.filestech.agenda_tech.widget.AgendaWidget]
 * masque de force les titres dès qu'un PIN existe — mais cette décision ne vaut que pour le rendu
 * **suivant**, et `agenda_widget_info.xml` demande à la plateforme une période de 30 minutes. Sans
 * un redessin immédiat, les titres que le verrou vient d'être activé pour cacher restent lisibles
 * une demi-heure.
 *
 * La phrase a déjà été fausse une fois : elle était écrite dans la KDoc d'`AgendaWidget` alors que
 * `updateAll` n'avait **qu'un seul appelant** dans tout le dépôt, après une restauration. Rien ne
 * l'appliquait à l'activation du verrou.
 *
 * ## Ce que ce test rattrape, précisément
 *
 * L'audit de cohérence C2 a fait passer `SettingsViewModel.refreshWidget()` de sa propre copie du
 * geste à [AgendaChangeNotifier]. Une migration est exactement le moment où une garantie se perd en
 * silence : le code compile, l'écran se comporte pareil, et seul le widget — que personne ne regarde
 * pendant un test — cesse d'être redessiné. Ce test échoue si l'appel disparaît de l'un des trois
 * chemins qui changent ce que le widget a le droit d'afficher.
 *
 * Le `rearmReminders = false` est vérifié **nommément**, et ce n'est pas du zèle : ces trois réglages
 * ne peuvent pas déplacer un rappel. Passer `true` relirait tous les rappels et déplierait leurs
 * récurrences avant de redessiner, ce qui retarderait précisément le geste dont l'intérêt est d'être
 * immédiat.
 */
class SettingsViewModelWidgetRefreshTest {

    private val dispatcher = StandardTestDispatcher()
    private val settings = FakeSettingsRepository()
    private val lock: LockRepository = mockk(relaxed = true)
    private val agendaChanged: AgendaChangeNotifier = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { lock.lockEnabled } returns flowOf(false)
        every { lock.biometricEnabled } returns flowOf(false)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = SettingsViewModel(
        settingsRepository = settings,
        lockRepository = lock,
        appLock = mockk<AppLockManager>(relaxed = true),
        reminderNotifier = mockk<ReminderNotifier>(relaxed = true),
        biometricGate = mockk<BiometricGate>(relaxed = true),
        agendaChanged = agendaChanged,
    )

    @Test
    fun `setting a PIN redraws the widget immediately`() = runTest(dispatcher) {
        coEvery { lock.setPin(any()) } returns true
        val vm = viewModel()

        vm.setPin("1234")
        testScheduler.advanceUntilIdle()

        verify(exactly = 1) { agendaChanged.onAgendaChanged(rearmReminders = false) }
    }

    /**
     * Le contrôle négatif du précédent, et la moitié que l'audit AG-10 avait déjà trouvée manquante.
     *
     * Si le Keystore refuse d'envelopper l'empreinte, `setPin` rend `false` et **rien n'est écrit** :
     * le verrou n'est pas actif. Redessiner alors serait faux dans les deux sens — inutile, et surtout
     * cela donnerait à croire que la garantie a joué là où aucun verrou n'a été posé.
     */
    @Test
    fun `a refused PIN redraws nothing`() = runTest(dispatcher) {
        coEvery { lock.setPin(any()) } returns false
        val vm = viewModel()

        vm.setPin("1234")
        testScheduler.advanceUntilIdle()

        verify(exactly = 0) { agendaChanged.onAgendaChanged(any()) }
    }

    /** Retirer le verrou change aussi ce que le widget a le droit d'afficher — dans l'autre sens. */
    @Test
    fun `disabling the lock redraws the widget immediately`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.disableLock()
        testScheduler.advanceUntilIdle()

        verify(exactly = 1) { agendaChanged.onAgendaChanged(rearmReminders = false) }
    }

    /** La préférence explicite « masquer les titres dans le widget », troisième et dernier chemin. */
    @Test
    fun `hiding widget titles redraws the widget immediately`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.setWidgetHideTitles(true)
        testScheduler.advanceUntilIdle()

        verify(exactly = 1) { agendaChanged.onAgendaChanged(rearmReminders = false) }
    }
}
