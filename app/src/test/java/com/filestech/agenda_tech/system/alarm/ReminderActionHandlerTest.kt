package com.filestech.agenda_tech.system.alarm

import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.usecase.FakeEventRepository
import com.filestech.agenda_tech.system.notifications.ReminderNotifier
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The three reminder actions differ in what they must *not* do — two of them post the very same
 * notification. A snoozed alarm that rolled the series forward again would skip the occurrence after
 * this one, silently, with no error anywhere. That is the failure these tests exist for.
 */
class ReminderActionHandlerTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val occurrence: Long =
        LocalDateTime.of(2026, 7, 20, 9, 0).atZone(zone).toInstant().toEpochMilli()

    private val eventRepo = FakeEventRepository()
    private val notifier: ReminderNotifier = mockk(relaxed = true)
    private val scheduler: ReminderScheduler = mockk(relaxed = true)

    private val handler = ReminderActionHandler(eventRepo, notifier, scheduler)

    private val event = Event(
        id = 10,
        calendarId = 1,
        title = "Dentiste",
        startUtcMillis = occurrence,
        endUtcMillis = occurrence + 3_600_000,
        timeZoneId = zone.id,
    ).also { eventRepo.rows[10] = it }

    private suspend fun handle(action: String?, reminderId: Long = 5L, eventId: Long = 10L) =
        handler.handle(action, reminderId, eventId, occurrence)

    @Test
    fun `a fired alarm posts the reminder and rolls the series forward`() = runTest {
        handle(ReminderReceiver.ACTION_FIRE)

        coVerify { notifier.postReminder(event, occurrence) }
        coVerify { scheduler.onReminderFired(5L, 10L, occurrence) }
    }

    @Test
    fun `tapping snooze dismisses the notification and re-arms it, without touching the series`() = runTest {
        handle(ReminderReceiver.ACTION_SNOOZE)

        coVerify { notifier.cancelReminder(10L, occurrence) }
        coVerify { scheduler.snooze(10L, occurrence) }
        // Snoozing is not "the reminder fired": the series already moved on when it first did.
        coVerify(exactly = 0) { scheduler.onReminderFired(any(), any(), any()) }
        // …and no new notification is posted now — the snoozed alarm will do that in ten minutes.
        coVerify(exactly = 0) { notifier.postReminder(any(), any()) }
    }

    @Test
    fun `a snoozed alarm re-posts the reminder and does NOT roll the series forward again`() = runTest {
        handle(ReminderReceiver.ACTION_SNOOZE_FIRE)

        coVerify { notifier.postReminder(event, occurrence) }
        // The occurrence after this one would otherwise be skipped — silently.
        coVerify(exactly = 0) { scheduler.onReminderFired(any(), any(), any()) }
    }

    @Test
    fun `an unknown action does nothing`() = runTest {
        assertThat(handler.handles("com.example.SOMETHING_ELSE")).isFalse()
        handle("com.example.SOMETHING_ELSE")

        coVerify(exactly = 0) { notifier.postReminder(any(), any()) }
        coVerify(exactly = 0) { notifier.cancelReminder(any(), any()) }
        coVerify(exactly = 0) { scheduler.snooze(any(), any()) }
        coVerify(exactly = 0) { scheduler.onReminderFired(any(), any(), any()) }
    }

    @Test
    fun `a null action does nothing`() = runTest {
        assertThat(handler.handles(null)).isFalse()
        handle(null)

        coVerify(exactly = 0) { notifier.postReminder(any(), any()) }
        coVerify(exactly = 0) { scheduler.snooze(any(), any()) }
    }

    @Test
    fun `the three real actions are recognised`() {
        assertThat(handler.handles(ReminderReceiver.ACTION_FIRE)).isTrue()
        assertThat(handler.handles(ReminderReceiver.ACTION_SNOOZE)).isTrue()
        assertThat(handler.handles(ReminderReceiver.ACTION_SNOOZE_FIRE)).isTrue()
    }

    @Test
    fun `an alarm for an event since deleted posts nothing`() = runTest {
        eventRepo.rows.remove(10)

        handle(ReminderReceiver.ACTION_SNOOZE_FIRE)

        coVerify(exactly = 0) { notifier.postReminder(any(), any()) }
    }

    @Test
    fun `an intent with no event id is ignored`() = runTest {
        handle(ReminderReceiver.ACTION_FIRE, eventId = -1L)

        coVerify(exactly = 0) { notifier.postReminder(any(), any()) }
        coVerify(exactly = 0) { scheduler.onReminderFired(any(), any(), any()) }
    }
}
