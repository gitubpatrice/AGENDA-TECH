package com.filestech.agenda_tech.ui.screens.month

import android.widget.Toast
import com.filestech.agenda_tech.ui.util.AddFab
import com.filestech.agenda_tech.ui.util.rememberAppResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.domain.ImportLimits
import com.filestech.agenda_tech.domain.birthday.displayTitle
import com.filestech.agenda_tech.domain.settings.MonthDisplay
import com.filestech.agenda_tech.ui.CalendarScaffold
import com.filestech.agenda_tech.ui.ics.IcsResult
import com.filestech.agenda_tech.ui.ics.IcsViewModel
import com.filestech.agenda_tech.ui.navigation.CalendarView
import com.filestech.agenda_tech.ui.theme.LogoShape
import com.filestech.agenda_tech.ui.theme.BrandDanger
import com.filestech.agenda_tech.ui.util.DatePickerModal
import com.filestech.agenda_tech.ui.util.rememberAppLocale
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

// A large virtual page window centred on the anchor month lets the pager scroll ~100 years either way.
private const val PAGER_PAGE_COUNT = 2400
private const val PAGER_ANCHOR_PAGE = PAGER_PAGE_COUNT / 2
private const val MONTHS_PER_YEAR = 12

@Composable
fun MonthScreen(
    onSelectView: (CalendarView) -> Unit,
    onAddEvent: (LocalDate) -> Unit,
    onOccurrenceClick: (Long, Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenBackup: () -> Unit,
    viewModel: MonthViewModel = hiltViewModel(),
    icsViewModel: IcsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val display by viewModel.display.collectAsStateWithLifecycle()
    val showRestorePrompt by viewModel.showRestorePrompt.collectAsStateWithLifecycle()
    val backupPrompt by viewModel.backupPrompt.collectAsStateWithLifecycle()
    val icsResult by icsViewModel.result.collectAsStateWithLifecycle()
    val icsBusy by icsViewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val exportLauncher = rememberAppResultLauncher(
        ActivityResultContracts.CreateDocument("text/calendar"),
    ) { uri -> uri?.let(icsViewModel::export) }
    val importLauncher = rememberAppResultLauncher(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(icsViewModel::import) }

    // Resources rather than LocalContext: reading through the context does not observe a
    // configuration change, so after a language switch the toast would still be in the old one.
    val resources = LocalResources.current
    LaunchedEffect(icsResult, resources) {
        val message = when (val result = icsResult) {
            // <plurals> depuis l'audit : « 1 events » s'affichait dans le cas le plus courant.
            is IcsResult.Exported ->
                resources.getQuantityString(R.plurals.ics_export_ok, result.count, result.count)
            is IcsResult.Imported ->
                resources.getQuantityString(R.plurals.ics_import_ok, result.count, result.count)
            IcsResult.TooManyEvents ->
                resources.getString(R.string.ics_import_too_many, ImportLimits.MAX_EVENTS)
            IcsResult.Failed -> resources.getString(R.string.ics_error)
            null -> null
        }
        if (message != null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            icsViewModel.consumeResult()
        }
    }

    // Audit de coherence C4 — l'import lit, analyse puis ecrit jusqu'a 5 Mo de `.ics` sans qu'aucun
    // signe ne parte a l'ecran : il restait parfaitement immobile jusqu'au message final, ce qui se
    // lit comme « rien ne s'est passe ». Le voile capte aussi les tapes, ce qui evite d'ouvrir
    // l'editeur sur un agenda en train d'etre remplace.
    Box(modifier = Modifier.fillMaxSize()) {
        MonthScreenContent(
            state = state,
            display = display,
            onSelectView = onSelectView,
            onPreviousMonth = viewModel::onPreviousMonth,
            onNextMonth = viewModel::onNextMonth,
            onToday = viewModel::onToday,
            onShowMonth = viewModel::showMonth,
            onSelectDate = viewModel::onSelectDate,
            onDisplayChange = viewModel::setDisplay,
            onAddEvent = onAddEvent,
            onOccurrenceClick = onOccurrenceClick,
            onExportIcs = { exportLauncher.launch("agenda-tech.ics") },
            onImportIcs = { importLauncher.launch(arrayOf("text/calendar", "*/*")) },
            onOpenSettings = onOpenSettings,
            onOpenAbout = onOpenAbout,
            onOpenSearch = onOpenSearch,
            showRestorePrompt = showRestorePrompt,
            onRestoreBackup = {
                // Answered either way — restoring or declining. Don't ask again.
                viewModel.dismissRestorePrompt()
                onOpenBackup()
            },
            onDismissRestorePrompt = viewModel::dismissRestorePrompt,
            backupPrompt = backupPrompt,
            onBackupNow = onOpenBackup,
            onSnoozeBackupPrompt = viewModel::snoozeBackupPrompt,
        )
        if (icsBusy) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
                    // Absorbe les tapes : sans lui, le voile est purement decoratif et l'utilisateur
                    // ouvre l'editeur sur des lignes que l'import est en train de remplacer.
                    .clickable(enabled = true, onClick = {}, indication = null,
                        interactionSource = remember { MutableInteractionSource() }),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun MonthScreenContent(
    state: MonthUiState,
    display: MonthDisplay?,
    onSelectView: (CalendarView) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
    onShowMonth: (YearMonth) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onDisplayChange: (MonthDisplay) -> Unit,
    onAddEvent: (LocalDate) -> Unit,
    onOccurrenceClick: (Long, Long) -> Unit,
    onExportIcs: () -> Unit,
    onImportIcs: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSearch: () -> Unit,
    showRestorePrompt: Boolean,
    onRestoreBackup: () -> Unit,
    onDismissRestorePrompt: () -> Unit,
    backupPrompt: BackupPromptReason?,
    onBackupNow: () -> Unit,
    onSnoozeBackupPrompt: () -> Unit,
) {
    val locale = rememberAppLocale()
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    // A request to bring a day into view in the rows display — "Today", the date picker. A counter and
    // its target rather than the selection itself: see MonthRows.
    var scrollRequest by rememberSaveable { mutableIntStateOf(0) }
    var scrollTargetDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val requestScrollTo: (LocalDate) -> Unit = { date ->
        scrollTargetDay = date.toEpochDay()
        scrollRequest++
    }

    CalendarScaffold(
        currentView = CalendarView.MONTH,
        onSelectView = onSelectView,
        topBar = {
            TopAppBar(
                title = {
                    // The month name opens a date picker: a month a year away is one tap, not twelve swipes.
                    // The arrow says the title can be tapped, which a title usually cannot.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable(
                                onClickLabel = stringResource(R.string.month_pick_date),
                                role = Role.Button,
                            ) { pickingDate = true }
                            .padding(end = 4.dp),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.app_logo),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp).clip(LogoShape),
                        )
                        Text(
                            text = monthLabel(state.yearMonth, locale),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                },
                actions = {
                    // Its place is kept while the setting loads, so the icons beside it do not shift.
                    if (display != null) {
                        MonthDisplayMenu(current = display, onSelect = onDisplayChange)
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }
                    IconButton(onClick = onOpenSearch) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = stringResource(R.string.search_open),
                        )
                    }
                    MonthOverflowMenu(
                        onExportIcs = onExportIcs,
                        onImportIcs = onImportIcs,
                        onOpenSettings = onOpenSettings,
                        onOpenAbout = onOpenAbout,
                    )
                },
            )
        },
        floatingActionButton = {
            AddFab(
                onClick = { onAddEvent(state.selectedDate) },
                contentDescription = stringResource(R.string.month_add_event),
            )
        },
    ) { innerPadding ->
        val today = remember { LocalDate.now(ZoneId.systemDefault()) }
        val pagerState = rememberPagerState(initialPage = pageForMonth(state.yearMonth)) { PAGER_PAGE_COUNT }

        // Pager settled on a page → tell the ViewModel which month is now shown.
        LaunchedEffect(pagerState.settledPage) {
            onShowMonth(monthForPage(pagerState.settledPage))
        }
        // Month changed elsewhere (Today, arrows, tapping an adjacent-month day) → move the pager.
        LaunchedEffect(state.yearMonth) {
            val target = pageForMonth(state.yearMonth)
            // The date picker is held within the pager's years, so this guard should never trip; it
            // keeps a month the pager cannot reach from becoming an out-of-range page.
            if (target in 0 until PAGER_PAGE_COUNT && pagerState.currentPage != target) {
                pagerState.animateScrollToPage(target)
            }
        }

        val haptic = LocalHapticFeedback.current
        val latestDisplay by rememberUpdatedState(display)
        val latestOnDisplayChange by rememberUpdatedState(onDisplayChange)
        // With the titles, the grid fills the screen and has no list under it: a tap on a day opens the
        // day over the grid. Saved, so coming back from the editor reopens the day just edited.
        var daySheetOpen by rememberSaveable { mutableStateOf(false) }
        // Selected first, so on coming back from the editor the grid shows the day the event went to.
        val onDayLongClick: (LocalDate) -> Unit = { date ->
            onSelectDate(date)
            onAddEvent(date)
        }

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .pinchSteps { spread ->
                    val current = latestDisplay ?: return@pinchSteps
                    val next = if (spread) current.moreDetail() else current.lessDetail()
                    if (next != current) {
                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        latestOnDisplayChange(next)
                    }
                },
        ) {
            // Mutually exclusive by construction: one needs an empty agenda, the other a full one.
            if (showRestorePrompt) {
                PromptCard(
                    icon = Icons.Outlined.SettingsBackupRestore,
                    title = stringResource(R.string.restore_prompt_title),
                    body = stringResource(R.string.restore_prompt_body),
                    dismissLabel = stringResource(R.string.restore_prompt_dismiss),
                    actionLabel = stringResource(R.string.restore_prompt_action),
                    onDismiss = onDismissRestorePrompt,
                    onAction = onRestoreBackup,
                )
            }
            backupPrompt?.let { reason ->
                PromptCard(
                    icon = Icons.Outlined.CloudOff,
                    title = stringResource(R.string.backup_prompt_title),
                    body = stringResource(
                        when (reason) {
                            BackupPromptReason.NEVER -> R.string.backup_prompt_body
                            BackupPromptReason.STALE -> R.string.backup_prompt_stale_body
                        },
                    ),
                    dismissLabel = stringResource(R.string.backup_prompt_later),
                    actionLabel = stringResource(R.string.backup_prompt_action),
                    onDismiss = onSnoozeBackupPrompt,
                    onAction = onBackupNow,
                    tint = BrandDanger,
                )
            }

            // Month navigation row (moved out of the crowded app bar).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPreviousMonth) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.month_previous),
                    )
                }
                TextButton(onClick = {
                    onToday()
                    requestScrollTo(LocalDate.now(ZoneId.systemDefault()))
                }) { Text(stringResource(R.string.month_today)) }
                IconButton(onClick = onNextMonth) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.month_next),
                    )
                }
            }
            // Nothing is drawn below the navigation row until the display is known: a few milliseconds of
            // empty space, rather than one layout and then the jump to another.
            val shown = display ?: return@Column
            // The rows carry their own weekday names.
            if (shown != MonthDisplay.ROWS) {
                WeekdayHeader(state.firstDayOfWeek, locale, state.showWeekNumbers)
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (shown == MonthDisplay.DOTS) Modifier else Modifier.weight(1f)),
                verticalAlignment = Alignment.Top,
            ) { page ->
                val pageMonth = monthForPage(page)
                // The shown month and its two neighbours arrive filled in (see MonthViewModel); only a page
                // further away — the pager crossing several months after "Today" — is drawn bare, for the
                // instant it is on screen.
                val weeks = state.pages[pageMonth] ?: bareWeeks(pageMonth, state, today)
                when (shown) {
                    MonthDisplay.DOTS -> MonthGridRows(
                        weeks = weeks,
                        showWeekNumbers = state.showWeekNumbers,
                        locale = locale,
                        onSelectDate = onSelectDate,
                        onDayLongClick = onDayLongClick,
                    )
                    MonthDisplay.TITLES -> MonthTitlesGrid(
                        weeks = weeks,
                        showWeekNumbers = state.showWeekNumbers,
                        locale = locale,
                        onDayClick = { date ->
                            onSelectDate(date)
                            daySheetOpen = true
                        },
                        onDayLongClick = onDayLongClick,
                        modifier = Modifier.fillMaxSize(),
                    )
                    MonthDisplay.ROWS -> MonthRows(
                        weeks = weeks,
                        firstDayOfWeek = state.firstDayOfWeek,
                        showWeekNumbers = state.showWeekNumbers,
                        scrollRequest = scrollRequest,
                        scrollTarget = LocalDate.ofEpochDay(scrollTargetDay),
                        locale = locale,
                        onDayClick = onSelectDate,
                        onDayLongClick = onDayLongClick,
                        onOccurrenceClick = onOccurrenceClick,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (shown == MonthDisplay.DOTS) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = dayLabel(state.selectedDate, locale),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                SelectedDayOccurrences(
                    occurrences = state.selectedDayOccurrences,
                    locale = locale,
                    onOccurrenceClick = onOccurrenceClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
        if (daySheetOpen && display == MonthDisplay.TITLES) {
            DaySheet(
                date = state.selectedDate,
                occurrences = state.selectedDayOccurrences,
                locale = locale,
                onChangeDay = onSelectDate,
                onAddEvent = onAddEvent,
                onOccurrenceClick = onOccurrenceClick,
                onDismiss = { daySheetOpen = false },
            )
        }
    }

    if (pickingDate) {
        DatePickerModal(
            initialDate = state.selectedDate,
            onConfirm = { date ->
                pickingDate = false
                onSelectDate(date)
                requestScrollTo(date)
            },
            onDismiss = { pickingDate = false },
            // Only the years the pager can show (external review: a date beyond them sent the pager out
            // of range).
            yearRange = PAGER_YEARS,
        )
    }
}

/**
 * Page and month, on a FIXED reference: January 2000 on the middle page, so the 2400 pages run from
 * January 1900 to December 2099. The reference used to be the month shown when the screen appeared —
 * but the pager saves its page across a trip to another screen, and the reference was taken again on
 * the way back: after a swipe to November, a visit to Settings came back on December, one month
 * further at every return (pre-release audit, 2026-10-05; the defect was already in 1.1.1).
 */
private fun monthForPage(page: Int): YearMonth = PAGER_REFERENCE.plusMonths((page - PAGER_ANCHOR_PAGE).toLong())

private fun pageForMonth(month: YearMonth): Int =
    PAGER_ANCHOR_PAGE + (month.year - PAGER_REFERENCE.year) * MONTHS_PER_YEAR +
        (month.monthValue - PAGER_REFERENCE.monthValue)

private val PAGER_REFERENCE: YearMonth = YearMonth.of(2000, 1)

/** The years the pager covers, within the date picker's own range. */
private val PAGER_YEARS: IntRange = IntRange(
    monthForPage(0).year.coerceAtLeast(DatePickerDefaults.YearRange.first),
    monthForPage(PAGER_PAGE_COUNT - 1).year.coerceAtMost(DatePickerDefaults.YearRange.last),
)

/** A month the ViewModel has not read (more than one away from the shown one): the dates alone. */
private fun bareWeeks(month: YearMonth, state: MonthUiState, today: LocalDate): List<List<DayCellData>> =
    MonthGrid.weeks(month, state.firstDayOfWeek).map { row ->
        row.map { date ->
            DayCellData(
                date = date,
                isInMonth = YearMonth.from(date) == month,
                isToday = date == today,
                isSelected = date == state.selectedDate,
                events = emptyList(),
            )
        }
    }

/** The 6×7 day grid for one month page (ISO week numbers from the mid-week cell when enabled). */
@Composable
private fun MonthGridRows(
    weeks: List<List<DayCellData>>,
    showWeekNumbers: Boolean,
    locale: Locale,
    onSelectDate: (LocalDate) -> Unit,
    onDayLongClick: (LocalDate) -> Unit,
) {
    val addLabel = stringResource(R.string.month_add_event)
    // 56 dp at the default text size; taller when the text is enlarged, so the grown circle keeps the
    // dots below it instead of pushing them out of the cell.
    val cellHeight = maxOf(
        DOTS_CELL_HEIGHT,
        dayNumberDiameter(DOTS_NUMBER_SIZE, MaterialTheme.typography.labelLarge) + DOTS_CELL_HEIGHT - DOTS_NUMBER_SIZE,
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        weeks.forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                if (showWeekNumbers) {
                    val weekNumber = week[MonthGrid.DAYS_PER_WEEK / 2].date.get(WeekFields.ISO.weekOfWeekBasedYear())
                    WeekNumberCell(weekNumber, Modifier.height(cellHeight))
                }
                week.forEach { cell -> DayCell(cell, cellHeight, locale, addLabel, onSelectDate, onDayLongClick) }
            }
        }
    }
}

@Composable
private fun WeekdayHeader(firstDayOfWeek: DayOfWeek, locale: Locale, showWeekNumbers: Boolean) {
    Row(modifier = Modifier.fillMaxWidth()) {
        if (showWeekNumbers) {
            Box(modifier = Modifier.width(weekNumberWidth()))
        }
        MonthGrid.weekdayHeaders(firstDayOfWeek).forEach { dow ->
            Text(
                text = dow.getDisplayName(TextStyle.SHORT, locale),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun RowScope.DayCell(
    cell: DayCellData,
    height: Dp,
    locale: Locale,
    addLabel: String,
    onClick: (LocalDate) -> Unit,
    onLongClick: (LocalDate) -> Unit,
) {
    // The dots say nothing to a screen reader: the count is said in words with the date.
    val description = dayDescription(cell, locale, withCount = true)
    Column(
        modifier = Modifier
            .weight(1f)
            .height(height)
            .clip(MaterialTheme.shapes.small)
            .selectedDayOutline(cell.isSelected, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
            .dayGestures(
                onClick = { onClick(cell.date) },
                onLongClick = { onLongClick(cell.date) },
                longClickLabel = addLabel,
            )
            .semantics {
                contentDescription = description
                selected = cell.isSelected
            }
            .padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DayNumber(cell, size = DOTS_NUMBER_SIZE, style = MaterialTheme.typography.labelLarge)
        EventDots(cell.events)
    }
}

@Composable
internal fun SelectedDayOccurrences(
    occurrences: List<OccurrenceData>,
    locale: Locale,
    onOccurrenceClick: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (occurrences.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.month_no_events),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val zone = remember { ZoneId.systemDefault() }
    LazyColumn(modifier = modifier) {
        items(occurrences, key = { it.eventId to it.startUtcMillis }) { occurrence ->
            OccurrenceRow(occurrence, zone, locale, onOccurrenceClick)
        }
    }
}

@Composable
private fun OccurrenceRow(
    occurrence: OccurrenceData,
    zone: ZoneId,
    locale: Locale,
    onOccurrenceClick: (Long, Long) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOccurrenceClick(occurrence.eventId, occurrence.startUtcMillis) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color(occurrence.colorArgb)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayTitle(occurrence.title, occurrence.birthdayAge),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = timeLabel(occurrence, zone, locale),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- formatting helpers (UI-side, locale-aware) -----------------------------

@Composable
private fun MonthOverflowMenu(
    onExportIcs: () -> Unit,
    onImportIcs: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // Wrap the button + menu in a Box so the DropdownMenu anchors to the icon (top-right) instead of
    // floating to the screen edge.
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // Leading icons make each entry identifiable at a glance (Files Tech convention).
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_settings)) },
                leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                onClick = {
                    expanded = false
                    onOpenSettings()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_import_ics)) },
                leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                onClick = {
                    expanded = false
                    onImportIcs()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_export_ics)) },
                leadingIcon = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
                onClick = {
                    expanded = false
                    onExportIcs()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_about)) },
                leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                onClick = {
                    expanded = false
                    onOpenAbout()
                },
            )
        }
    }
}

private fun monthLabel(yearMonth: YearMonth, locale: Locale): String {
    val month = yearMonth.month.getDisplayName(TextStyle.FULL, locale)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    return "$month ${yearMonth.year}"
}

internal fun dayLabel(date: LocalDate, locale: Locale): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

@Composable
private fun timeLabel(occurrence: OccurrenceData, zone: ZoneId, locale: Locale): String {
    if (occurrence.allDay) return stringResource(R.string.month_all_day)
    val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    val start = Instant.ofEpochMilli(occurrence.startUtcMillis).atZone(zone).format(formatter)
    val end = Instant.ofEpochMilli(occurrence.endUtcMillis).atZone(zone).format(formatter)
    return "$start – $end"
}

/**
 * The banner the Month screen uses to say something that matters about the user's data — offering a
 * restore on an empty agenda, or a backup on a full one.
 *
 * One composable for both: they are the same object with different words, and two copies of a card
 * drift into two slightly different cards.
 */
@Composable
private fun PromptCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    dismissLabel: String,
    actionLabel: String,
    onDismiss: () -> Unit,
    onAction: () -> Unit,
    tint: androidx.compose.ui.graphics.Color? = null,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
        border = BorderStroke(1.dp, cs.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint ?: cs.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurface,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text(dismissLabel) }
                // Filled like every main action of the app (Unlock, Save, the "+"): the offer is the
                // point of the card, "later" only its way out.
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/** Opacite du voile pose pendant un import/export `.ics` : assez sombre pour dire « attendez »,
 * assez clair pour que l'agenda reste reconnaissable derriere. */
private const val SCRIM_ALPHA = 0.32f

private val DOTS_CELL_HEIGHT = 56.dp
private val DOTS_NUMBER_SIZE = 26.dp
