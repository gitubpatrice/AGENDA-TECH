package com.filestech.agenda_tech.system.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.model.RecurrenceFreq
import com.filestech.agenda_tech.domain.model.RecurrenceRule
import com.filestech.agenda_tech.domain.model.Reminder
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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * After the reminder of an all-day day rings, the next one goes to the following day.
 *
 * The case that matters is a series created in a zone AHEAD of the phone's: its stored midnights come
 * before the phone's, so a threshold built from the stored instant of the day that just rang still lets
 * that same day through — the alarm would ring again for it, at once. The scheduler places the fired day
 * on the phone's calendar first, as every threshold of `ReminderScheduling` is.
 */
class ReminderSchedulerAllDayTest {

    private val phone: ZoneId = ZoneId.systemDefault()
    private val ahead: ZoneId = ZoneId.of("Pacific/Kiritimati") // UTC+14, no daylight saving

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

    private fun midnight(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    @BeforeEach
    fun setUp() {
        // Needs a phone well behind UTC+14 for the two midnights to differ by hours.
        assumeTrue(phone.rules.getOffset(Instant.now()).totalSeconds <= 10 * 3600)
        mockkStatic(PendingIntent::class)
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(PendingIntent::class)
    }

    @Test
    fun `the day after rings next, not the day that just rang`() = runTest {
        val first = LocalDate.now(phone).minusDays(3)
        eventRepo.rows[1] = Event(
            id = 1,
            calendarId = 1,
            title = "Holiday",
            startUtcMillis = midnight(first, ahead),
            endUtcMillis = midnight(first.plusDays(1), ahead),
            timeZoneId = ahead.id,
            allDay = true,
            recurrence = RecurrenceRule(RecurrenceFreq.DAILY),
        )
        reminderRepo.rows[10] = Reminder(id = 10, eventId = 1, minutesBefore = 0)
        val rang = LocalDate.now(phone).plusDays(5)

        scheduler.onReminderFired(reminderId = 10, eventId = 1, firedOccurrenceStartUtcMillis = midnight(rang, ahead))

        verify { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, midnight(rang.plusDays(1), phone), any()) }
        verify(exactly = 0) { alarmManager.setExactAndAllowWhileIdle(any(), midnight(rang, phone), any()) }
    }
}
