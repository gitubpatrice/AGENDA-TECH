package com.filestech.agenda_tech.ui.util

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import com.filestech.agenda_tech.R

/**
 * "+2" where a day has more than it can show — said in words to a screen reader, which would otherwise
 * read "plus two". The month's titles and the week's all-day row share it.
 */
@Composable
fun MoreEventsLabel(hidden: Int, style: TextStyle, modifier: Modifier = Modifier) {
    val more = pluralStringResource(R.plurals.month_more_events, hidden, hidden)
    Text(
        text = "+$hidden",
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { contentDescription = more },
    )
}
