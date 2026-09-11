package com.filestech.agenda_tech.ui.deviceimport

import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.usecase.FakeCalendarRepository
import com.filestech.agenda_tech.domain.usecase.FakeDeviceCalendars
import com.filestech.agenda_tech.domain.usecase.FakeEventRepository
import com.filestech.agenda_tech.domain.usecase.ImportDeviceEventsUseCase
import com.filestech.agenda_tech.system.AgendaChangeNotifier
import com.filestech.agenda_tech.system.alarm.ReminderScheduler
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * **L'écran d'import ne doit jamais rester verrouillé.**
 *
 * ## Pourquoi ce fichier existe
 *
 * `DeviceImportScreen` avale le geste de retour par `BackHandler(enabled = importing)` et désactive
 * le bouton retour pendant l'opération — à raison, pour qu'on ne détruise pas le ViewModel en vol.
 * La contrepartie est qu'un drapeau `importing` bloqué à `true` ne laisse **aucune sortie** :
 * l'utilisateur ne peut ni réessayer, ni quitter l'écran. Ce n'est pas un drapeau coincé, c'est un
 * cul-de-sac.
 *
 * Or la chaîne d'écriture peut lever, et elle n'est gardée nulle part ailleurs :
 * `importedEventIds()` fait deux lectures Room et `clearImported()` un DELETE SQLCipher brut. Le
 * `runCatching` d'`ImportDeviceEventsUseCase` ne couvre que la boucle par calendrier de l'import,
 * pas ces deux-là.
 *
 * ## Ce que ce test rattrape, précisément
 *
 * `import()` et `clearImported()` posaient `_importing = true` puis lançaient leur corps **sans
 * `try/finally`**. Le garde avait pourtant été posé le même jour sur `EventEditorViewModel`,
 * `CalendarsViewModel` et `IcsViewModel` — et pas ici, alors que ce même jour ajoutait du code neuf
 * *à l'intérieur* de ces deux blocs (`onAgendaChanged()` et le désarmement AG-9). C'est le chemin
 * jumeau pour la sixième fois en une journée, créé par la correction même qui le traquait, et trouvé
 * par l'audit pré-release et non par ma propre relecture.
 */
class DeviceImportViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val calendars = FakeCalendarRepository()
    private val events = FakeEventRepository()
    private val devices = FakeDeviceCalendars()

    private val scheduler: ReminderScheduler = mockk(relaxed = true)
    private val agendaChanged: AgendaChangeNotifier = mockk(relaxed = true)

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = DeviceImportViewModel(
        importDeviceEvents = ImportDeviceEventsUseCase(devices, calendars, events),
        reminderScheduler = scheduler,
        agendaChanged = agendaChanged,
    )

    /**
     * **Le test qui manquait.** Sans le `finally`, `importing` reste à `true` pour toujours et
     * l'écran n'a plus de sortie.
     */
    @Test
    fun `a throwing wipe releases the screen instead of locking it`() = runTest(dispatcher) {
        calendars.stored += Calendar(id = 1, name = "Perso", isDefault = true)
        calendars.stored += Calendar(id = 2, name = "Google", sourceId = "device:7")
        val vm = viewModel()

        calendars.failNextDeleteImported = true
        vm.clearImported()
        testScheduler.advanceUntilIdle()

        assertThat(vm.importing.value).isFalse()
        // L'échec est DIT : un écran qui verrouille le retour et ne dit rien laisse croire à un gel.
        assertThat(vm.failed.value).isTrue()
        // Et rien n'a été effacé — le message ne ment pas.
        assertThat(calendars.stored.map { it.id }).containsExactly(1L, 2L)
    }

    /** La preuve que l'écran n'est pas condamné : la tentative suivante aboutit. */
    @Test
    fun `the screen accepts a retry after a failure`() = runTest(dispatcher) {
        calendars.stored += Calendar(id = 1, name = "Perso", isDefault = true)
        calendars.stored += Calendar(id = 2, name = "Google", sourceId = "device:7")
        val vm = viewModel()

        calendars.failNextDeleteImported = true
        vm.clearImported()
        testScheduler.advanceUntilIdle()
        vm.consumeFailed()

        vm.clearImported()
        testScheduler.advanceUntilIdle()

        assertThat(vm.importing.value).isFalse()
        assertThat(vm.failed.value).isFalse()
        assertThat(vm.cleared.value).isTrue()
        // `deleteImported` ne touche que les calendriers importés, jamais celui fait à la main.
        assertThat(calendars.stored.map { it.id }).containsExactly(1L)
    }

    /**
     * Le contrôle négatif du premier test : sur le chemin nominal, ni `failed` ni blocage.
     *
     * Sans lui, un `catch` trop large qui avalerait tout ferait passer le premier test alors que
     * l'écran ne marcherait plus du tout.
     */
    @Test
    fun `the nominal wipe reports success, not failure`() = runTest(dispatcher) {
        calendars.stored += Calendar(id = 1, name = "Perso", isDefault = true)
        calendars.stored += Calendar(id = 2, name = "Google", sourceId = "device:7")
        val vm = viewModel()

        vm.clearImported()
        testScheduler.advanceUntilIdle()

        assertThat(vm.failed.value).isFalse()
        assertThat(vm.cleared.value).isTrue()
        assertThat(vm.importing.value).isFalse()
    }

    /**
     * `listCalendars()` interroge le fournisseur système : une permission révoquée entre l'octroi et
     * la requête lève `SecurityException`. L'appel n'était gardé par rien.
     *
     * L'état d'échec est tenu distinct d'`Empty` à dessein — dire « aucun calendrier sur cet
     * appareil » à quelqu'un dont la permission vient d'être retirée est une réponse fausse à une
     * question qu'il n'a pas posée.
     */
    @Test
    fun `a provider that throws yields Failed, never Empty`() = runTest(dispatcher) {
        devices.failNextListCalendars = true
        val vm = viewModel()

        vm.onPermissionGranted()
        testScheduler.advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(DeviceImportViewModel.UiState.Failed)
    }
}
