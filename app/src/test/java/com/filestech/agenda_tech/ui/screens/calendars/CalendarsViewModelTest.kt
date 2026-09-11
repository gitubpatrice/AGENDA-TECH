package com.filestech.agenda_tech.ui.screens.calendars

import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.usecase.FakeCalendarRepository
import com.filestech.agenda_tech.domain.usecase.FakeEventRepository
import com.filestech.agenda_tech.domain.usecase.UpsertCalendarUseCase
import com.filestech.agenda_tech.system.AgendaChangeNotifier
import com.filestech.agenda_tech.system.alarm.ReminderScheduler
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import kotlinx.coroutines.launch
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
 * Supprimer un calendrier doit **désarmer les alarmes de ses événements**, et le faire AVANT la
 * cascade (audit AG-9).
 *
 * ## Pourquoi ce fichier existe
 *
 * `promoteDefaultAndDelete` efface le calendrier, donc ses événements puis leurs rappels par cascade
 * de clé étrangère. Les alarmes déjà posées auprès d'`AlarmManager`, elles, ne sont annulées par
 * personne : `rescheduleAll()` itère `reminderRepository.getAll()`, c'est-à-dire ce qui existe
 * ENCORE en base. Une ligne que la cascade vient d'effacer n'y figure plus.
 *
 * Isolées, ces orphelines sont bénignes — les identifiants sont `AUTOINCREMENT`, donc au tir
 * `getById` rend `null` et rien n'est posté. Le danger est en chaîne : une restauration réinsère les
 * identifiants **verbatim** depuis le fichier, et l'orpheline retrouve alors un AUTRE événement
 * portant son numéro. Elle poste un rappel pour le mauvais événement, à la mauvaise heure. C'est le
 * scénario F7, par une porte que F7 ne fermait pas.
 *
 * ## Ce que ce test rattrape, précisément
 *
 * Une première version du correctif AG-9 se contentait d'appeler `agendaChanged.onAgendaChanged()`
 * après la suppression. Le commentaire décrivait le scénario ci-dessus ; l'appel ne le traitait pas,
 * puisque `rescheduleAll()` ne peut rien désarmer qui n'existe plus. **Aucun test ne couvrait ce
 * chemin**, et c'est cette absence qui a laissé passer le correctif incomplet — signalé par la
 * plongée sécurité du 2026-09-11.
 */
class CalendarsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val calendars = FakeCalendarRepository()
    private val events = FakeEventRepository()

    /** Mocké : `ReminderScheduler` est final et câblé à `AlarmManager`. Ce qui compte est l'appel. */
    private val scheduler: ReminderScheduler = mockk(relaxed = true)
    private val agendaChanged: AgendaChangeNotifier = mockk(relaxed = true)

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = CalendarsViewModel(
        calendarRepository = calendars,
        upsertCalendar = UpsertCalendarUseCase(calendars),
        eventRepository = events,
        reminderScheduler = scheduler,
        agendaChanged = agendaChanged,
    )

    private fun seed() {
        calendars.stored += Calendar(id = 1, name = "Perso", isDefault = true)
        calendars.stored += Calendar(id = 2, name = "Travail")
        // Deux événements dans le calendrier qui va disparaître, un dans celui qui reste.
        events.rows[42] = Event(
            id = 42, calendarId = 2, title = "Réunion",
            startUtcMillis = 1_000, endUtcMillis = 2_000, timeZoneId = "Europe/Paris",
        )
        events.rows[43] = Event(
            id = 43, calendarId = 2, title = "Point",
            startUtcMillis = 3_000, endUtcMillis = 4_000, timeZoneId = "Europe/Paris",
        )
        events.rows[7] = Event(
            id = 7, calendarId = 1, title = "Dentiste",
            startUtcMillis = 5_000, endUtcMillis = 6_000, timeZoneId = "Europe/Paris",
        )
    }

    @Test
    fun `deleting a calendar disarms the alarms of every event it held`() = runTest(dispatcher) {
        seed()
        val vm = viewModel()
        // `uiState` est un `stateIn(WhileSubscribed)` : sans abonne il reste a sa valeur initiale
        // (liste vide) et `delete()` sort immediatement sur son `firstOrNull`. L'ecran reel, lui,
        // collecte — un test qui ne collecte pas testerait un ViewModel dans un etat que personne
        // ne rencontre.
        backgroundScope.launch { vm.uiState.collect { } }
        testScheduler.advanceUntilIdle()

        vm.delete(2)
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { scheduler.cancelEvent(42) }
        coVerify(exactly = 1) { scheduler.cancelEvent(43) }
    }

    @Test
    fun `the alarms of the calendars that stay are left alone`() = runTest(dispatcher) {
        seed()
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }
        testScheduler.advanceUntilIdle()

        vm.delete(2)
        testScheduler.advanceUntilIdle()

        // Le contrôle négatif du test précédent : un désarmement trop large couperait les rappels
        // d'événements que l'utilisateur n'a pas touchés, ce qui est le défaut symétrique et pire.
        coVerify(exactly = 0) { scheduler.cancelEvent(7) }
        assertThat(events.rows.keys).contains(7L)
    }
}
