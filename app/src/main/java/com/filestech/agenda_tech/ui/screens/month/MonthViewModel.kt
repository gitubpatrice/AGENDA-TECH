package com.filestech.agenda_tech.ui.screens.month

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.core.time.DeviceZone
import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.domain.recurrence.EventOccurrence
import com.filestech.agenda_tech.domain.recurrence.shownEndUtcMillis
import com.filestech.agenda_tech.domain.recurrence.shownStartUtcMillis
import com.filestech.agenda_tech.domain.repository.CalendarRepository
import com.filestech.agenda_tech.domain.repository.EventRepository
import com.filestech.agenda_tech.domain.repository.SettingsRepository
import com.filestech.agenda_tech.domain.settings.MonthDisplay
import com.filestech.agenda_tech.domain.settings.toDayOfWeek
import com.filestech.agenda_tech.domain.usecase.ObserveOccurrencesInRangeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import com.filestech.agenda_tech.core.time.DAY_MILLIS
import com.filestech.agenda_tech.domain.birthday.BirthdayAge
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject

/**
 * Drives the month view: tracks the displayed [YearMonth] and selected day, streams the occurrences
 * of the whole visible grid ([ObserveOccurrencesInRangeUseCase]) and folds them — with calendar
 * colours and the user's first-day-of-week / week-number settings — into a [MonthUiState].
 */
@HiltViewModel
class MonthViewModel @Inject constructor(
    private val observeOccurrences: ObserveOccurrencesInRangeUseCase,
    private val calendarRepository: CalendarRepository,
    private val eventRepository: EventRepository,
    private val settingsRepository: SettingsRepository,
    private val deviceZone: DeviceZone,
) : ViewModel() {

    /**
     * Overridable clock. The backup rule is entirely about elapsed time, so a test has to be able to
     * pin "now" — otherwise "has it been two months?" is not a question that can be asserted.
     */
    @VisibleForTesting
    internal var nowUtcMillis: () -> Long = System::currentTimeMillis

    /**
     * The phone's zone now. Read at each use, and the query below follows [DeviceZone.zone]: taken once
     * at construction, an open month kept the zone it was opened in after a journey.
     */
    private val zone: ZoneId get() = deviceZone.zone.value

    /**
     * The language the screen is drawn in, which decides the first day of the week when the setting
     * says "system". Sent by the screen ([onLocaleChange]) rather than read here once: the view model
     * outlives a change of the app's language, and the grid kept the old language's first day — a
     * German month starting on Sunday — while every label had already turned German.
     */
    private val appLocale = MutableStateFlow(Locale.getDefault())

    private val displayedMonth = MutableStateFlow(YearMonth.now(zone))
    private val selectedDate = MutableStateFlow(LocalDate.now(zone))

    private val firstDayOfWeekFlow = combine(settingsRepository.settings, appLocale) { settings, locale ->
        settings.weekStart.toDayOfWeek(locale)
    }.distinctUntilChanged()

    // Audit DATA-4 — only the settings this view actually uses, so unrelated toggles don't rebuild the grid.
    private val monthSettingsFlow = combine(settingsRepository.settings, appLocale) { settings, locale ->
        settings.weekStart.toDayOfWeek(locale) to settings.showWeekNumbers
    }.distinctUntilChanged()

    /**
     * How the month is drawn — kept out of [uiState] on purpose. [uiState] waits for the database, and
     * opening it took up to three seconds on the S9 after a PIN unlock: someone who had chosen the
     * titles saw the dots layout all that time, then the jump. The display comes from the settings
     * alone, read in milliseconds. Null until then, so the screen draws no layout rather than a wrong one.
     */
    val display: StateFlow<MonthDisplay?> = settingsRepository.settings
        .map { it.monthDisplay }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    // The two neighbouring months are read along with the shown one, so a swipe slides in a page that is
    // already filled in. With dots, a page filling itself once it settles went unnoticed; with titles in
    // the grid it is plainly visible.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val windowOccurrences = combine(displayedMonth, firstDayOfWeekFlow, deviceZone.zone) { month, firstDay, now ->
        Triple(month, firstDay, now)
    }.flatMapLatest { (month, firstDay, windowZone) ->
        val startDate = MonthGrid.gridRange(month.minusMonths(1), firstDay).first
        val endDate = MonthGrid.gridRange(month.plusMonths(1), firstDay).second
        observeOccurrences(
            startDate.atStartOfDay(windowZone).toInstant().toEpochMilli(),
            endDate.atStartOfDay(windowZone).toInstant().toEpochMilli(),
            windowZone,
        ).map { occurrences -> windowZone to occurrences }
    }

    // The last values the two database sources gave, replayed whenever they start again. The occurrences
    // keep the zone they were read in, and the state is built in that zone: a list read before a journey
    // and days counted after it would put events on the wrong day until the new query answered
    // (external review, 2026-10-05).
    private var lastWindow: Pair<ZoneId, List<EventOccurrence>> = zone to emptyList()
    private var lastCalendars: List<Calendar> = emptyList()

    // The two database sources start with their last values: the grid is then drawn as soon as the
    // settings are read — with the user's first day of the week — and the events follow from the
    // database. Waiting for it drew the locale's first day for the seconds the database took after a
    // PIN unlock, then the jump to the user's (external review, 2026-10-05). The LAST values and not an
    // empty list: these flows restart each time the screen comes back after five seconds away, and an
    // empty start blanked the grid on every return from the editor (pre-release audit, same day). On
    // the very first start there is nothing to replay, which is what lets the grid appear early.
    // Applied to the outer flow, not to each month's query, so a swipe never empties the grid.
    val uiState: StateFlow<MonthUiState> = combine(
        displayedMonth,
        selectedDate,
        windowOccurrences.onEach { lastWindow = it }.onStart { emit(lastWindow) },
        calendarRepository.observeAll().onEach { lastCalendars = it }.onStart { emit(lastCalendars) },
        monthSettingsFlow,
    ) { month, selected, (windowZone, occurrences), calendars, settingsPair ->
        buildState(
            zone = windowZone,
            month = month,
            selected = selected,
            occurrences = occurrences,
            colorByCalendarId = calendars.associate { it.id to it.color.argb },
            firstDayOfWeek = settingsPair.first,
            showWeekNumbers = settingsPair.second,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = buildState(
            zone = zone,
            month = YearMonth.now(zone),
            selected = LocalDate.now(zone),
            occurrences = emptyList(),
            colorByCalendarId = emptyMap(),
            firstDayOfWeek = WeekFields.of(appLocale.value).firstDayOfWeek,
            showWeekNumbers = false,
            isLoading = true,
        ),
    )

    private val agendaStats = eventRepository.observeStats().distinctUntilChanged()

    /**
     * Whether to offer restoring a backup.
     *
     * Shown only on an agenda that holds nothing at all — which is exactly the state of a fresh
     * install after a new phone, the one moment the backup exists for. Without this, the user has to
     * already know the feature exists and go looking for it in Settings → Privacy.
     *
     * Kept out of [MonthUiState]: that flow already combines five sources (the typed limit), and this
     * one has nothing to do with drawing the grid.
     */
    val showRestorePrompt: StateFlow<Boolean> = combine(
        agendaStats,
        settingsRepository.settings.map { it.restorePromptDismissed }.distinctUntilChanged(),
    ) { stats, dismissed -> stats.isEmpty && !dismissed }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /**
     * Whether to remind the user to back up.
     *
     * The app's whole promise is that the data lives nowhere but this phone — which is also to say
     * that a lost phone with no backup loses everything. Offering a backup feature and never
     * mentioning it again leaves that entirely to chance.
     *
     * Unlike [showRestorePrompt], this one *must* come back: backing up is recurring by nature, and a
     * reminder asked once would only ever be declined once. So the cost is paid where it belongs — it
     * is made rare and earned, never merely periodic:
     *
     *  - nothing worth protecting yet (under [MIN_EVENTS_TO_SUGGEST_BACKUP]) → silent;
     *  - "later" → silent for [SNOOZE_DAYS] days, not forever;
     *  - already exported, and nothing has changed since → silent, however long ago that was. Nagging
     *    someone whose agenda has not moved would be pure noise.
     */
    val backupPrompt: StateFlow<BackupPromptReason?> = combine(
        agendaStats,
        settingsRepository.settings,
    ) { stats, settings ->
        val now = nowUtcMillis()
        when {
            stats.eventCount < MIN_EVENTS_TO_SUGGEST_BACKUP -> null
            now < settings.backupPromptSnoozedUntilUtcMillis -> null
            settings.lastBackupAtUtcMillis == 0L -> BackupPromptReason.NEVER
            // Exported before: only speak up if there is something new to lose, and enough time has
            // passed that saying so is worth the interruption.
            stats.lastChangeAtUtcMillis > settings.lastBackupAtUtcMillis &&
                now - settings.lastBackupAtUtcMillis > STALE_BACKUP_DAYS * DAY_MILLIS ->
                BackupPromptReason.STALE
            else -> null
        }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * The user answered the offer — restored, or waved it away. Either way it must not come back:
     * a deliberately empty agenda would otherwise be nagged on every single launch.
     */
    fun dismissRestorePrompt() = viewModelScope.launch {
        settingsRepository.update { it.copy(restorePromptDismissed = true) }
    }

    /** "Later" on the backup reminder — quiet for [SNOOZE_DAYS] days, then it may ask again. */
    fun snoozeBackupPrompt() = viewModelScope.launch {
        val until = nowUtcMillis() + SNOOZE_DAYS * DAY_MILLIS
        settingsRepository.update { it.copy(backupPromptSnoozedUntilUtcMillis = until) }
    }

    fun onPreviousMonth() = moveTo(displayedMonth.value.minusMonths(1))

    fun onNextMonth() = moveTo(displayedMonth.value.plusMonths(1))

    fun onToday() {
        displayedMonth.value = YearMonth.now(zone)
        selectedDate.value = LocalDate.now(zone)
    }

    /** Jump straight to a month (used by the swipe pager once it settles on a page). */
    fun showMonth(month: YearMonth) {
        if (displayedMonth.value != month) moveTo(month)
    }

    /**
     * Shows [month] and brings the selection into it: today in the current month, the 1st in any
     * other. Left behind, the selected day kept its list under a month it no longer belonged to, and
     * two months away — outside the window read — that list said "no events" for a day that had some
     * (external review, 2026-10-05). The 1st rather than the same day of the month, so the rows open
     * at the top of the month they show.
     */
    private fun moveTo(month: YearMonth) {
        if (YearMonth.from(selectedDate.value) != month) {
            selectedDate.value = if (month == YearMonth.now(zone)) LocalDate.now(zone) else month.atDay(1)
        }
        displayedMonth.value = month
    }

    /** The language the screen is drawn in — see [appLocale]. */
    fun onLocaleChange(locale: Locale) {
        appLocale.value = locale
    }

    /** Remembered across launches: the button row and the pinch both land here. */
    fun setDisplay(display: MonthDisplay) = viewModelScope.launch {
        settingsRepository.update { it.copy(monthDisplay = display) }
    }

    /** Selecting a leading/trailing cell that belongs to an adjacent month navigates to it. */
    fun onSelectDate(date: LocalDate) {
        selectedDate.value = date
        val month = YearMonth.from(date)
        if (month != displayedMonth.value) displayedMonth.value = month
    }

    private fun buildState(
        zone: ZoneId,
        month: YearMonth,
        selected: LocalDate,
        occurrences: List<EventOccurrence>,
        colorByCalendarId: Map<Long, Int>,
        firstDayOfWeek: DayOfWeek,
        showWeekNumbers: Boolean,
        isLoading: Boolean,
    ): MonthUiState {
        val today = LocalDate.now(zone)

        // Converted and sorted once for the whole window; every day below is a filter of this list, so
        // the dots, the titles, the rows and the selected day's list all agree on order and content.
        val sorted = occurrences
            .map {
                OccurrenceData(
                    eventId = it.event.id,
                    title = it.event.title,
                    startUtcMillis = it.shownStartUtcMillis(zone),
                    endUtcMillis = it.shownEndUtcMillis(zone),
                    occurrenceStartUtcMillis = it.startUtcMillis,
                    allDay = it.event.allDay,
                    colorArgb = colorOf(it, colorByCalendarId),
                    birthdayAge = BirthdayAge.of(it.event, it.startUtcMillis, zone),
                )
            }
            .sortedWith(compareBy({ !it.allDay }, { it.startUtcMillis }))

        val pages = PAGE_OFFSETS.associate { offset ->
            val pageMonth = month.plusMonths(offset)
            pageMonth to MonthGrid.weeks(pageMonth, firstDayOfWeek).map { week ->
                week.map { date ->
                    DayCellData(
                        date = date,
                        isInMonth = YearMonth.from(date) == pageMonth,
                        isToday = date == today,
                        isSelected = date == selected,
                        events = sorted.overlapping(date, zone),
                    )
                }
            }
        }
        // ISO week number, read from the mid-week cell so it's stable whatever the first day is.
        val weekNumbers = pages.getValue(month)
            .map { it[MID_WEEK_INDEX].date.get(WeekFields.ISO.weekOfWeekBasedYear()) }

        return MonthUiState(
            zone = zone,
            yearMonth = month,
            firstDayOfWeek = firstDayOfWeek,
            pages = pages,
            selectedDate = selected,
            selectedDayOccurrences = sorted.overlapping(selected, zone),
            showWeekNumbers = showWeekNumbers,
            weekNumbers = weekNumbers,
            isLoading = isLoading,
        )
    }

    /**
     * FIAB-2 — every occurrence that overlaps [date], not only those starting on it, so a multi-day or
     * all-day-span event (a 2-day holiday) shows on each day it covers.
     */
    private fun List<OccurrenceData>.overlapping(date: LocalDate, zone: ZoneId): List<OccurrenceData> {
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return filter { it.startUtcMillis < dayEnd && it.endUtcMillis > dayStart }
    }

    private fun colorOf(occurrence: EventOccurrence, colorByCalendarId: Map<Long, Int>): Int =
        occurrence.event.colorOverride?.argb
            ?: colorByCalendarId[occurrence.event.calendarId]
            ?: CalendarColor.DEFAULT.argb

    companion object {
        /**
         * Below this, an agenda holds nothing worth the interruption — someone trying the app out is
         * not someone to warn about data loss.
         */
        const val MIN_EVENTS_TO_SUGGEST_BACKUP = 10

        /** After an export, how long before changes are worth mentioning again. */
        const val STALE_BACKUP_DAYS = 60L

        /** How long "later" buys. Long enough not to nag, short enough to still matter. */
        const val SNOOZE_DAYS = 14L

        const val STOP_TIMEOUT_MS = 5_000L
        const val MID_WEEK_INDEX = 3

        /** The shown month and its two neighbours — see [windowOccurrences]. */
        private val PAGE_OFFSETS = -1L..1L
    }
}
