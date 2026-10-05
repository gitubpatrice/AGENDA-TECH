package com.filestech.agenda_tech.ui.screens.editor

import com.filestech.agenda_tech.core.time.FarZones
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The zone the editor reads an event in and writes it back in: a timed event's own zone kept unless its
 * time is retyped, an all-day event's dates read and written in its own zone wherever the phone is.
 */
internal class EventEditorTimeZoneTest : EventEditorTestBase() {

    // --- Audit D2 : le fuseau d'un événement existant ne doit pas être écrasé ---

    @Test
    fun `saving an imported event keeps the zone it was authored in`() = runTest(dispatcher) {
        // The whole point of the v6 repair migration is that a row imported from Outlook ends up with
        // a zone every reader can resolve. Opening that event to add a reminder used to overwrite it
        // with the device zone, which silently undid the repair — and, since the expander re-anchors
        // recurring occurrences to this field, moved every future occurrence by an hour across the
        // next DST transition.
        val tokyo = "Asia/Tokyo"
        eventRepo.rows[10] = seedEvent().copy(timeZoneId = tokyo)
        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()

        vm.onAddReminder(15)
        vm.onSave()
        testScheduler.advanceUntilIdle()

        assertThat(eventRepo.rows.getValue(10).timeZoneId).isEqualTo(tokyo)
    }

    @Test
    fun `retyping the time re-anchors the event to the device zone`() = runTest(dispatcher) {
        // ⚠️ This test asserted the OPPOSITE until audit DR-6, and the opposite was a defect I had
        // pinned with a test — the worst way to be wrong, because it makes the defect load-bearing.
        //
        // The reasoning that produced it stopped one step early. Preserving the authored zone is right
        // when the user opens an event to add a reminder (that is D2). It is wrong the moment they
        // retype a time, because this editor reads and writes wall-clock times in the DEVICE zone and
        // offers no zone picker: typing 11:00 in Paris on a meeting authored in Asia/Tokyo produces
        // the instant 18:00 JST. Keeping the Tokyo label then makes RecurrenceExpander anchor every
        // later occurrence to 18:00 JST — Japan has no summer time, France does, so after October the
        // user sees 10:00 for a series they set to 11:00. F3's symptom, roles reversed.
        //
        // The only honest reading of a time typed here is "local", so a moved event is re-anchored.
        eventRepo.rows[10] = seedEvent().copy(timeZoneId = "Asia/Tokyo")
        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()

        vm.onStartTimeChange(11, 0)
        vm.onSave()
        testScheduler.advanceUntilIdle()

        val saved = eventRepo.rows.getValue(10)
        assertThat(saved.startUtcMillis).isEqualTo(at(2026, 7, 20, 11))
        assertThat(saved.timeZoneId).isEqualTo(zone.id)
    }

    @Test
    fun `a zone the app cannot resolve is not preserved`() = runTest(dispatcher) {
        // Audit DR-5. Before D2 the editor overwrote this field with the device zone and therefore
        // repaired any unresolvable row by accident; preserving the loaded value removed that safety
        // net along with the defect. `EntityMappers` deliberately does not re-normalise on read, so
        // this is the last place the invariant can be kept.
        eventRepo.rows[10] = seedEvent().copy(timeZoneId = "Romance Standard Time")
        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()

        vm.onAddReminder(15)
        vm.onSave()
        testScheduler.advanceUntilIdle()

        assertThat(eventRepo.rows.getValue(10).timeZoneId).isEqualTo(zone.id)
    }

    @Test
    fun `a new event is authored in the device zone`() = runTest(dispatcher) {
        // The other half: with nothing to preserve, the device zone is the right answer. A test that
        // only pinned the first half would be satisfied by never writing the field at all.
        val vm = viewModel()
        vm.onTitleChange("Nouveau")
        vm.onSave()
        testScheduler.advanceUntilIdle()

        assertThat(eventRepo.rows.values.single { it.title == "Nouveau" }.timeZoneId).isEqualTo(zone.id)
    }

    @Test
    fun `an all-day event keeps its own zone, its boundaries computed in it`() = runTest(dispatcher) {
        // For an all-day row the zone is not a label but part of the arithmetic: the instants ARE
        // midnight-to-midnight in this zone, and every view reads the dates back in it. Its boundaries
        // are therefore computed in its own zone and stored beside it, never in the phone's: this test
        // asserted the phone's zone until the dates were read in the event's own one, and that rewrite
        // is what moved such an event by a day.
        val tokyo = ZoneId.of("Asia/Tokyo")
        val date = LocalDate.of(2026, 7, 20)
        seedAllDayElsewhere(date, tokyo)
        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()

        vm.onEndDateChange(date.plusDays(1))
        vm.onSave()
        testScheduler.advanceUntilIdle()

        val saved = eventRepo.rows.getValue(10)
        assertThat(saved.timeZoneId).isEqualTo(tokyo.id)
        assertThat(saved.startUtcMillis).isEqualTo(date.atStartOfDay(tokyo).toInstant().toEpochMilli())
        assertThat(saved.endUtcMillis).isEqualTo(date.plusDays(2).atStartOfDay(tokyo).toInstant().toEpochMilli())
    }

    @Test
    fun `a timed event turned all-day takes the dates it showed, in the phone's zone`() = runTest(dispatcher) {
        // A timed event is read on the phone's clock, so its date is the one the user saw there; the new
        // all-day event is counted in the phone's zone, like any the user creates.
        eventRepo.rows[10] = seedEvent().copy(timeZoneId = "Asia/Tokyo")
        val shownDate = Instant.ofEpochMilli(eventRepo.rows.getValue(10).startUtcMillis).atZone(zone).toLocalDate()
        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()

        vm.onAllDayChange(true)
        vm.onSave()
        testScheduler.advanceUntilIdle()

        val saved = eventRepo.rows.getValue(10)
        assertThat(saved.timeZoneId).isEqualTo(zone.id)
        assertThat(saved.startUtcMillis).isEqualTo(shownDate.atStartOfDay(zone).toInstant().toEpochMilli())
    }

    // --- Journée entière créée dans un autre fuseau ----------------------------

    private fun seedAllDayElsewhere(
        date: LocalDate,
        elsewhere: ZoneId,
        recurrence: RecurrenceRule? = null,
    ): Event = Event(
        id = 10,
        calendarId = 1,
        title = "Congé",
        startUtcMillis = date.atStartOfDay(elsewhere).toInstant().toEpochMilli(),
        endUtcMillis = date.plusDays(1).atStartOfDay(elsewhere).toInstant().toEpochMilli(),
        timeZoneId = elsewhere.id,
        allDay = true,
        recurrence = recurrence,
    ).also { eventRepo.rows[10] = it }

    @ParameterizedTest
    @ValueSource(strings = [FarZones.AHEAD, FarZones.BEHIND])
    fun `an all-day event created in another zone opens on its own date`(elsewhereId: String) = runTest(dispatcher) {
        // Read on the phone's clock, its midnights fall on the day before or after: the editor showed
        // the wrong date for anything made before a journey, or imported from a file written elsewhere.
        val date = LocalDate.of(2026, 7, 20)
        seedAllDayElsewhere(date, ZoneId.of(elsewhereId))

        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()

        assertThat(vm.state.value.startDateTime.toLocalDate()).isEqualTo(date)
        assertThat(vm.state.value.endDateTime.toLocalDate()).isEqualTo(date)
    }

    @ParameterizedTest
    @ValueSource(strings = [FarZones.AHEAD, FarZones.BEHIND])
    fun `saving an all-day event from another zone without changes leaves it where it was`(elsewhereId: String) = runTest(dispatcher) {
        // Opened to add a reminder, saved: the event moved by a day, without the user touching a date.
        val original = seedAllDayElsewhere(LocalDate.of(2026, 7, 20), ZoneId.of(elsewhereId))

        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()
        vm.onAddReminder(60)
        vm.onSave()
        testScheduler.advanceUntilIdle()

        val saved = eventRepo.rows.getValue(10)
        assertThat(saved.startUtcMillis).isEqualTo(original.startUtcMillis)
        assertThat(saved.endUtcMillis).isEqualTo(original.endUtcMillis)
        assertThat(saved.timeZoneId).isEqualTo(original.timeZoneId)
    }

    @ParameterizedTest
    @ValueSource(strings = [FarZones.AHEAD, FarZones.BEHIND])
    fun `renaming an all-day series from another zone keeps its cancelled occurrence cancelled`(elsewhereId: String) = runTest(dispatcher) {
        // Its EXDATEs name occurrences by the midnights of its own zone. Rewritten in the phone's zone,
        // the series would no longer produce those instants, and the cancelled day would come back.
        val elsewhere = ZoneId.of(elsewhereId)
        val first = LocalDate.of(2026, 7, 20)
        val cancelled = first.plusWeeks(1).atStartOfDay(elsewhere).toInstant().toEpochMilli()
        seedAllDayElsewhere(
            first,
            elsewhere,
            RecurrenceRule(freq = RecurrenceFreq.WEEKLY, exDatesUtcMillis = listOf(cancelled)),
        )

        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()
        vm.onTitleChange("Congé — renommé")
        vm.onSave()
        testScheduler.advanceUntilIdle()

        val saved = eventRepo.rows.getValue(10)
        val weeks = RecurrenceExpander().expand(
            saved,
            first.atStartOfDay(elsewhere).toInstant().toEpochMilli(),
            first.plusWeeks(3).atStartOfDay(elsewhere).toInstant().toEpochMilli(),
        )
        assertThat(weeks.map { it.startUtcMillis }).containsExactly(
            first.atStartOfDay(elsewhere).toInstant().toEpochMilli(),
            first.plusWeeks(2).atStartOfDay(elsewhere).toInstant().toEpochMilli(),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = [FarZones.AHEAD, FarZones.BEHIND])
    fun `an all-day series from another zone keeps its last day when saved`(elsewhereId: String) = runTest(dispatcher) {
        // The end date is counted in the series' own zone, like its occurrences: counted in the phone's,
        // the series gained or lost its last day.
        val elsewhere = ZoneId.of(elsewhereId)
        val first = LocalDate.of(2026, 7, 20)
        val lastDay = first.plusWeeks(2)
        val until = lastDay.plusDays(1).atStartOfDay(elsewhere).toInstant().toEpochMilli() - 1
        seedAllDayElsewhere(first, elsewhere, RecurrenceRule(freq = RecurrenceFreq.WEEKLY, untilUtcMillis = until))

        val vm = viewModel(eventId = 10)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.recurrenceUntilDate).isEqualTo(lastDay)
        vm.onSave()
        testScheduler.advanceUntilIdle()

        val saved = eventRepo.rows.getValue(10)
        assertThat(saved.recurrence!!.untilUtcMillis).isEqualTo(until)
    }

    @ParameterizedTest
    @ValueSource(strings = [FarZones.AHEAD, FarZones.BEHIND])
    fun `changing one day of an all-day series from another zone writes the override in the series' zone`(
        elsewhereId: String,
    ) = runTest(dispatcher) {
        val elsewhere = ZoneId.of(elsewhereId)
        val first = LocalDate.of(2026, 7, 20)
        seedAllDayElsewhere(first, elsewhere, RecurrenceRule(freq = RecurrenceFreq.WEEKLY))
        val occurrence = first.plusWeeks(1).atStartOfDay(elsewhere).toInstant().toEpochMilli()

        val vm = viewModel(eventId = 10, occurrenceStart = occurrence)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.startDateTime.toLocalDate()).isEqualTo(first.plusWeeks(1))
        vm.onTitleChange("Congé — ce jour-là")
        vm.onSave()
        testScheduler.advanceUntilIdle()
        vm.confirmScope(applyToSeries = false)
        testScheduler.advanceUntilIdle()

        val override = eventRepo.rows.values.single { it.recurrenceParentId == 10L }
        assertThat(override.originalStartUtcMillis).isEqualTo(occurrence)
        assertThat(override.startUtcMillis).isEqualTo(occurrence)
        assertThat(override.timeZoneId).isEqualTo(elsewhere.id)
        assertThat(eventRepo.rows.getValue(10).recurrence!!.exDatesUtcMillis).containsExactly(occurrence)
    }

    // --- La fin d'une occurrence, comptée comme les vues la comptent ---------

    @ParameterizedTest
    @ValueSource(strings = ["2026-03-29", "2026-10-18"])
    fun `one day of an all-day series opens as one day across a change of summer time`(firstDay: String) =
        runTest(dispatcher) {
            // Begun on the 23-hour day (29 March), or tapped on the 25-hour one (25 October): adding the
            // series' length in milliseconds ended the occurrence at 23:00, which the editor showed as
            // ending the day before it began — and saved as an all-day event of no length.
            val paris = ZoneId.of("Europe/Paris")
            val first = LocalDate.parse(firstDay)
            seedAllDayElsewhere(first, paris, RecurrenceRule(freq = RecurrenceFreq.WEEKLY))
            val tapped = first.plusWeeks(1)

            val vm = viewModel(eventId = 10, occurrenceStart = tapped.atStartOfDay(paris).toInstant().toEpochMilli())
            testScheduler.advanceUntilIdle()
            assertThat(vm.state.value.startDateTime.toLocalDate()).isEqualTo(tapped)
            assertThat(vm.state.value.endDateTime.toLocalDate()).isEqualTo(tapped)
            vm.onTitleChange("Congé — ce jour-là")
            vm.onSave()
            testScheduler.advanceUntilIdle()
            vm.confirmScope(applyToSeries = false)
            testScheduler.advanceUntilIdle()

            val override = eventRepo.rows.values.single { it.recurrenceParentId == 10L }
            assertThat(override.endUtcMillis).isEqualTo(tapped.plusDays(1).atStartOfDay(paris).toInstant().toEpochMilli())
        }
}
