package com.filestech.agenda_tech.ui.screens.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Height of one hour row on the timeline; the whole day is 24× this. */
val HOUR_HEIGHT: Dp = 60.dp

/** Left column of hour labels, aligned with the [EventsColumn] grid. */
@Composable
fun HourGutter(modifier: Modifier = Modifier) {
    Column(modifier.width(52.dp)) {
        for (hour in 0..23) {
            Box(
                modifier = Modifier
                    .height(HOUR_HEIGHT)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopEnd,
            ) {
                val label = if (hour < 10) "0$hour:00" else "$hour:00"
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
    }
}

/** A single day's timed events, positioned absolutely by minute with side-by-side lanes. */
@Composable
fun EventsColumn(
    positioned: List<PositionedItem<TimelineItem>>,
    nowMinute: Int?,
    onItemClick: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val minuteHeight = HOUR_HEIGHT / 60
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val nowColor = MaterialTheme.colorScheme.error

    BoxWithConstraints(modifier.height(HOUR_HEIGHT * 24)) {
        Column(Modifier.fillMaxSize()) {
            repeat(24) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(HOUR_HEIGHT)
                        .drawBehind {
                            drawLine(lineColor, Offset(0f, 0f), Offset(size.width, 0f), 1f)
                        },
                )
            }
        }

        positioned.forEach { block ->
            val laneWidth = maxWidth / block.laneCount
            Box(
                Modifier
                    .offset(
                        x = laneWidth * block.lane.toFloat(),
                        y = minuteHeight * block.startMinute.toFloat(),
                    )
                    .width(laneWidth)
                    .height(minuteHeight * (block.endMinute - block.startMinute).toFloat())
                    .padding(1.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(Color(block.item.colorArgb))
                    .clickable { onItemClick(block.item.eventId, block.item.startUtcMillis) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    text = block.item.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (nowMinute != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .offset(y = minuteHeight * nowMinute.toFloat())
                    .height(2.dp)
                    .background(nowColor),
            )
        }
    }
}

/** All-day events shown as a strip above the timeline. */
@Composable
fun AllDayStrip(
    items: List<TimelineItem>,
    onItemClick: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        items.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(Color(item.colorArgb))
                    .clickable { onItemClick(item.eventId, item.startUtcMillis) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
