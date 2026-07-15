package com.filestech.agenda_tech.ui.screens.month

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.ui.CalendarScaffold
import com.filestech.agenda_tech.ui.ics.IcsResult
import com.filestech.agenda_tech.ui.ics.IcsViewModel
import com.filestech.agenda_tech.ui.navigation.CalendarView
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.WeekFields
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

// A large virtual page window centred on the anchor month lets the pager scroll ~100 years either way.
private const val PAGER_PAGE_COUNT = 2400
private const val PAGER_ANCHOR_PAGE = PAGER_PAGE_COUNT / 2

@Composable
fun MonthScreen(
    onSelectView: (CalendarView) -> Unit,
    onAddEvent: (LocalDate) -> Unit,
    onOccurrenceClick: (Long, Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: MonthViewModel = hiltViewModel(),
    icsViewModel: IcsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val icsResult by icsViewModel.result.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar"),
    ) { uri -> uri?.let(icsViewModel::export) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(icsViewModel::import) }

    LaunchedEffect(icsResult) {
        val message = when (val result = icsResult) {
            is IcsResult.Exported -> context.getString(R.string.ics_export_ok, result.count)
            is IcsResult.Imported -> context.getString(R.string.ics_import_ok, result.count)
            IcsResult.Failed -> context.getString(R.string.ics_error)
            null -> null
        }
        if (message != null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            icsViewModel.consumeResult()
        }
    }

    MonthScreenContent(
        state = state,
        onSelectView = onSelectView,
        onPreviousMonth = viewModel::onPreviousMonth,
        onNextMonth = viewModel::onNextMonth,
        onToday = viewModel::onToday,
        onShowMonth = viewModel::showMonth,
        onSelectDate = viewModel::onSelectDate,
        onAddEvent = onAddEvent,
        onOccurrenceClick = onOccurrenceClick,
        onExportIcs = { exportLauncher.launch("agenda-tech.ics") },
        onImportIcs = { importLauncher.launch(arrayOf("text/calendar", "*/*")) },
        onOpenSettings = onOpenSettings,
        onOpenAbout = onOpenAbout,
    )
}

@Composable
private fun MonthScreenContent(
    state: MonthUiState,
    onSelectView: (CalendarView) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
    onShowMonth: (YearMonth) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onAddEvent: (LocalDate) -> Unit,
    onOccurrenceClick: (Long, Long) -> Unit,
    onExportIcs: () -> Unit,
    onImportIcs: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

    CalendarScaffold(
        currentView = CalendarView.MONTH,
        onSelectView = onSelectView,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.app_logo),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                        )
                        Text(
                            text = monthLabel(state.yearMonth, locale),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                },
                actions = {
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
            FloatingActionButton(onClick = { onAddEvent(state.selectedDate) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.month_add_event))
            }
        },
    ) { innerPadding ->
        val today = remember { LocalDate.now(ZoneId.systemDefault()) }
        // Anchor the pager on the month shown when the screen first appeared; each page is that
        // month ± an offset, so swiping slides smoothly to the adjacent month (follow-the-finger).
        val anchorMonth = remember { state.yearMonth }
        fun monthForPage(page: Int): YearMonth = anchorMonth.plusMonths((page - PAGER_ANCHOR_PAGE).toLong())
        fun pageForMonth(month: YearMonth): Int =
            PAGER_ANCHOR_PAGE + (month.year - anchorMonth.year) * 12 + (month.monthValue - anchorMonth.monthValue)

        val pagerState = rememberPagerState(initialPage = pageForMonth(state.yearMonth)) { PAGER_PAGE_COUNT }

        // Pager settled on a page → tell the ViewModel which month is now shown.
        LaunchedEffect(pagerState.settledPage) {
            onShowMonth(monthForPage(pagerState.settledPage))
        }
        // Month changed elsewhere (Today, arrows, tapping an adjacent-month day) → move the pager.
        LaunchedEffect(state.yearMonth) {
            val target = pageForMonth(state.yearMonth)
            if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
        }

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
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
                TextButton(onClick = onToday) { Text(stringResource(R.string.month_today)) }
                IconButton(onClick = onNextMonth) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.month_next),
                    )
                }
            }
            WeekdayHeader(state.firstDayOfWeek, locale, state.showWeekNumbers)
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                val pageMonth = monthForPage(page)
                // The settled month has its event dots from the ViewModel; the pages sliding in show
                // the bare grid (dots fill in once that month settles) — keeps swiping cheap and fluid.
                val weeks = if (pageMonth == state.yearMonth) {
                    state.weeks
                } else {
                    MonthGrid.weeks(pageMonth, state.firstDayOfWeek).map { row ->
                        row.map { date ->
                            DayCellData(
                                date = date,
                                isInMonth = YearMonth.from(date) == pageMonth,
                                isToday = date == today,
                                isSelected = date == state.selectedDate,
                                eventColors = emptyList(),
                                eventCount = 0,
                            )
                        }
                    }
                }
                MonthGridRows(
                    weeks = weeks,
                    showWeekNumbers = state.showWeekNumbers,
                    onSelectDate = onSelectDate,
                )
            }
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
}

/** The 6×7 day grid for one month page (ISO week numbers from the mid-week cell when enabled). */
@Composable
private fun MonthGridRows(
    weeks: List<List<DayCellData>>,
    showWeekNumbers: Boolean,
    onSelectDate: (LocalDate) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        weeks.forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                if (showWeekNumbers) {
                    val weekNumber = week[MonthGrid.DAYS_PER_WEEK / 2].date.get(WeekFields.ISO.weekOfWeekBasedYear())
                    WeekNumberCell(weekNumber)
                }
                week.forEach { cell -> DayCell(cell = cell, onClick = onSelectDate) }
            }
        }
    }
}

@Composable
private fun WeekNumberCell(weekNumber: Int) {
    Box(
        modifier = Modifier
            .width(24.dp)
            .height(56.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text = weekNumber.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun WeekdayHeader(firstDayOfWeek: DayOfWeek, locale: Locale, showWeekNumbers: Boolean) {
    Row(modifier = Modifier.fillMaxWidth()) {
        if (showWeekNumbers) {
            Box(modifier = Modifier.width(24.dp))
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
private fun RowScope.DayCell(cell: DayCellData, onClick: (LocalDate) -> Unit) {
    val numberColor = when {
        cell.isToday -> MaterialTheme.colorScheme.onPrimary
        !cell.isInMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .height(56.dp)
            .clip(MaterialTheme.shapes.small)
            .then(
                if (cell.isSelected) {
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                } else {
                    Modifier
                },
            )
            .clickable { onClick(cell.date) }
            .padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .then(
                    if (cell.isToday) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier,
                ),
        ) {
            Text(
                text = cell.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (cell.isToday) FontWeight.Bold else FontWeight.Normal,
                color = numberColor,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            cell.eventColors.forEach { argb ->
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(Color(argb)),
                )
            }
        }
    }
}

@Composable
private fun SelectedDayOccurrences(
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
                text = occurrence.title,
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

private fun dayLabel(date: LocalDate, locale: Locale): String =
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
