package com.filestech.agenda_tech.ui.screens.month

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.filestech.agenda_tech.R
import java.time.LocalDate
import java.util.Locale

/**
 * The day's events over the month grid, for the display where the grid fills the screen and has no
 * room left for the list under it.
 *
 * It stays open while the day changes — arrows, or a swipe across it — because reading a week one day
 * at a time should not cost a close and a tap per day. The day it shows is the selected day: moving in
 * the sheet moves the selection in the grid behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DaySheet(
    date: LocalDate,
    occurrences: List<OccurrenceData>,
    locale: Locale,
    onChangeDay: (LocalDate) -> Unit,
    onAddEvent: (LocalDate) -> Unit,
    onOccurrenceClick: (Long, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    // Opened at its full height straight away: half-open, a busy day kept its last events and the Add
    // button below the edge of the screen (measured on a day of six events).
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val latestDate by rememberUpdatedState(date)
        val latestOnChangeDay by rememberUpdatedState(onChangeDay)
        val thresholdPx = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }
        // A swipe towards the start of the line brings the next day, as a page turns — mirrored in a
        // right-to-left layout, where the next page lies on the other side.
        val forwardSign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1 else -1

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(forwardSign, thresholdPx) {
                    var dragged = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = {
                            when {
                                dragged * forwardSign >= thresholdPx -> latestOnChangeDay(latestDate.plusDays(1))
                                dragged * forwardSign <= -thresholdPx -> latestOnChangeDay(latestDate.minusDays(1))
                            }
                        },
                        onHorizontalDrag = { _, amount -> dragged += amount },
                    )
                }
                .padding(bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onChangeDay(date.minusDays(1)) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.day_previous))
                }
                Text(
                    text = dayLabel(date, locale),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onChangeDay(date.plusDays(1)) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.day_next))
                }
            }
            // Date and list travel together: keyed on the date alone, the outgoing day would slide away
            // already showing the incoming day's events. The weight lets a long day scroll inside the
            // sheet instead of pushing the Add button off the screen.
            AnimatedContent(
                targetState = SheetDay(date, occurrences),
                modifier = Modifier.weight(1f, fill = false),
                contentKey = { it.date },
                transitionSpec = {
                    val forward = targetState.date > initialState.date
                    // The next day enters from the side the swipe pulls it from: the right in a
                    // left-to-right layout, the left in a right-to-left one.
                    val enterFrom = if (forward) -forwardSign else forwardSign
                    (slideInHorizontally { it * enterFrom } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it * enterFrom } + fadeOut())
                },
                label = "daySheet",
            ) { day ->
                SelectedDayOccurrences(
                    occurrences = day.occurrences,
                    locale = locale,
                    onOccurrenceClick = onOccurrenceClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = LIST_MIN_HEIGHT),
                )
            }
            Button(
                onClick = { onAddEvent(date) },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 8.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.month_add_event))
            }
        }
    }
}

private data class SheetDay(val date: LocalDate, val occurrences: List<OccurrenceData>)

private val SWIPE_THRESHOLD = 56.dp
private val LIST_MIN_HEIGHT = 96.dp
