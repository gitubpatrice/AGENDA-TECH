package com.filestech.agenda_tech.ui.util

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The one way an event is listed in this app: a colour dot, its title, then whatever the screen has
 * to say underneath.
 *
 * Shared by the Agenda view and Search, which had grown byte-identical copies of it. Two copies of a
 * row do not break anything on the day they are written — they drift, and the app ends up listing the
 * same event two subtly different ways depending on which screen you came from.
 *
 * Only the frame is shared. What goes in [details] is each screen's business: Agenda shows a time
 * range, Search shows a full date plus the calendar the hit lives in.
 */
@Composable
fun EventRow(
    title: String,
    colorArgb: Int,
    onClick: () -> Unit,
    details: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(DOT_SIZE)
                .clip(CircleShape)
                .background(Color(colorArgb)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            details()
        }
    }
}

/** A secondary line under an event's title — the shared look for every detail line. */
@Composable
fun EventRowDetail(text: String, small: Boolean = false) {
    Text(
        text = text,
        style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private val DOT_SIZE = 12.dp
