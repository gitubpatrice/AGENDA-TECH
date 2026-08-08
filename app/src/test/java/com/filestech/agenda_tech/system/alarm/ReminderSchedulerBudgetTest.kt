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
import java.time.ZonedDateTime
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
    // Ancré sur "maintenant" et non sur une date littérale : `rescheduleAll` lit l'horloge réelle et
    // le balayage part du début de la série, donc une date fixe rend le coût d'expansion croissant
    // avec le temps — un test vert aujourd'hui qui rougit tout seul dans dix-huit mois.
    private val start = ZonedDateTime.now(zone).minusWeeks(4).withHour(9).toInstant().toEpochMilli()

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
    fun `a pathological series cannot spend the share of the reminders behind it`() = runTest {
        // Audit F5-ter. Event 1 is a weekly series running since the year 2000, so reaching "now"
        // costs well over a thousand iterations; event 2 is an ordinary one from this year. With ONE
        // instance shared across the pass — what F5 did — event 1 drained it and event 2 was never
        // armed. After a reboot that is not "the tail is not drawn this frame": exact alarms are gone,
        // so event 2's reminder simply never fires again until its event is saved.
        val ancient = ZonedDateTime.now(zone).minusYears(25).toInstant().toEpochMilli()
        eventRepo.rows[1] = eventRepo.rows.getValue(1).copy(
            startUtcMillis = ancient,
            endUtcMillis = ancient + 3_600_000,
            recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY),
        )
        eventRepo.rows[2] = eventRepo.rows.getValue(1).copy(
            id = 2,
            title = "Réunion",
            startUtcMillis = start,
            endUtcMillis = start + 3_600_000,
            recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY),
        )
        reminderRepo.rows.clear()
        reminderRepo.rows[10] = Reminder(id = 10, eventId = 1, minutesBefore = 15)
        reminderRepo.rows[11] = Reminder(id = 11, eventId = 2, minutesBefore = 15)

        // Event 1 is a 25-year weekly series (~1 300 iterations, above the per-reminder cap in the
        // scaled-down setup below); event 2 needs four. The pass total is ample — what must not happen
        // is event 1 eating it.
        // Pass total ample, per-reminder cap tight: exactly the shape the fix installs, scaled down
        // so a test can reach it. Event 1 wants ~1 300 and is capped at 100; event 2 wants ~4.
        scheduler.newPassBudget = { ExpansionBudget(20_000) }
        scheduler.perReminderIterations = 100

        scheduler.rescheduleAll()

        verify(atLeast = 1) {
            alarmManager.setExactAndAllowWhileIdle(any(), any(), any<PendingIntent>())
        }
        // And the starved one is still not cancelled — F5-bis must survive F5-ter.
        verify(exactly = 0) { alarmManager.cancel(any<PendingIntent>()) }
    }

    @Test
    fun `a homogeneous agenda is not decimated by the ceiling that protects it`() = runTest {
        // Audit F5-quater, found by an internal review of F5-ter — my own previous fix.
        //
        // F5-ter divided the pass allowance `total / n`. That is kind to a HETEROGENEOUS population,
        // where one greedy series is starved and the rest are fine. It is brutal on a homogeneous one,
        // which is the ordinary case: every reminder needs roughly the same number of iterations, so
        // once the share falls below that need, NONE of them is armed rather than the first 90 %.
        //
        // Ten reminders on identical two-year weekly series (~104 iterations each) and a pass total of
        // 400. Dividing gives each a share of 40 and arms zero. A pass ceiling plus a per-reminder cap
        // arms the first three or four and stops — which is the trade F5 actually asked for.
        val twoYears = ZonedDateTime.now(zone).minusYears(2).toInstant().toEpochMilli()
        eventRepo.rows.clear()
        reminderRepo.rows.clear()
        repeat(HOMOGENEOUS_COUNT) { i ->
            val id = (i + 1).toLong()
            eventRepo.rows[id] = Event(
                id = id,
                calendarId = 1,
                title = "Cours $i",
                startUtcMillis = twoYears,
                endUtcMillis = twoYears + 3_600_000,
                timeZoneId = zone.id,
                recurrence = RecurrenceRule(freq = RecurrenceFreq.WEEKLY),
            )
            reminderRepo.rows[id] = Reminder(id = id, eventId = id, minutesBefore = 15)
        }
        scheduler.newPassBudget = { ExpansionBudget(400) }

        scheduler.rescheduleAll()

        // The assertion F5-ter would fail: at least one reminder must come out of this armed.
        verify(atLeast = 1) {
            alarmManager.setExactAndAllowWhileIdle(any(), any(), any<PendingIntent>())
        }
        // And none of them disarmed on the way — F5-bis must survive F5-quater too.
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

    private companion object {
        const val HOMOGENEOUS_COUNT = 10
    }
}
