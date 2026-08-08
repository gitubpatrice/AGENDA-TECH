package com.filestech.agenda_tech.system.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.model.Reminder
import com.filestech.agenda_tech.domain.recurrence.ExpansionBudget
import com.filestech.agenda_tech.domain.recurrence.RecurrenceExpander
import com.filestech.agenda_tech.domain.usecase.FakeEventRepository
import com.filestech.agenda_tech.domain.usecase.FakeReminderRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * What [ReminderScheduler.rescheduleAll] does once its shared expansion budget is spent.
 *
 * ## Why this test exists
 *
 * Found by external review of lot D, and **introduced by lot D**. Before it, a null from
 * `computeNextFire` meant one thing — the series has no occurrence left — and cancelling the alarm
 * was the right answer. The budget gave null a second meaning, "we stopped looking", without
 * teaching the caller to tell them apart. Since `rescheduleAll` shares one budget across the whole
 * pass, exhausting it on one pathological series made every reminder after it look finished, and
 * each was cancelled.
 *
 * The damage is worse than the problem the budget was added to solve: not re-arming a reminder after
 * a reboot leaves it to the next app launch, whereas cancelling it means nothing re-arms it at all
 * until the user edits the event. That is the "fallback fails the wrong way" shape exactly.
 */
class ReminderSchedulerBudgetTest {

    private val zone: ZoneId = ZoneId.of("Europe/Paris")
    private val start = LocalDateTime.of(2026, 1, 5, 9, 0).atZone(zone).toInstant().toEpochMilli()

    private val alarmManager: AlarmManager = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val eventRepo = FakeEventRepository()
    private val reminderRepo = FakeReminderRepository()

    private val scheduler = ReminderScheduler(
        context = context,
        alarmManager = alarmManager,
        expander = RecurrenceExpander(),
        eventRepository = eventRepo,
        reminderRepository = reminderRepo,
    )

    @BeforeEach
    fun setUp() {
        // PendingIntent is a static Android factory; nothing here is testing it, and both the arm and
        // the cancel path go through it. A non-null stub keeps `cancel()` reaching alarmManager.cancel,
        // which is precisely the call these tests must see or not see.
        mockkStatic(PendingIntent::class)
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns mockk(relaxed = true)

        val event = Event(
            id = 1,
            calendarId = 1,
            title = "Cours",
            startUtcMillis = start,
            endUtcMillis = start + 3_600_000,
            timeZoneId = zone.id,
            recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY),
        )
        eventRepo.rows[1] = event
        reminderRepo.rows[10] = Reminder(id = 10, eventId = 1, minutesBefore = 15)
        reminderRepo.rows[11] = Reminder(id = 11, eventId = 1, minutesBefore = 30)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(PendingIntent::class)
    }

    @Test
    fun `a spent budget leaves the alarms alone instead of cancelling them`() = runTest {
        // One iteration for the whole pass: the first reminder consumes it, every later call to
        // computeNextFire then returns null for want of budget rather than for want of an occurrence.
        scheduler.newPassBudget = { ExpansionBudget(1) }

        scheduler.rescheduleAll()

        // The point of the whole fix. Cancelling here disarms reminders that are perfectly alive.
        verify(exactly = 0) { alarmManager.cancel(any<PendingIntent>()) }
    }

    @Test
    fun `a series that really has ended is still cancelled`() = runTest {
        // The other half, and the reason the fix is a budget check and not "never cancel": with room
        // to look, a null means what it always meant, and the alarm must go. A test asserting only
        // the first half would be satisfied by deleting the cancel entirely.
        val ended = eventRepo.rows.getValue(1).copy(
            recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY, count = 1),
            startUtcMillis = start - 400L * 24 * 3_600_000,
            endUtcMillis = start - 400L * 24 * 3_600_000 + 3_600_000,
        )
        eventRepo.rows[1] = ended
        scheduler.newPassBudget = { ExpansionBudget() }

        scheduler.rescheduleAll()

        verify(atLeast = 1) { alarmManager.cancel(any<PendingIntent>()) }
    }
}
