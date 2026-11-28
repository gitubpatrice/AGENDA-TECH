package com.filestech.agenda_tech.ui.util

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The line between the items of a list, light grey as asked on the S9: the theme's divider grey read as
 * too dark between the lines of a day. Shared, so every list draws the same line.
 */
@Composable
fun lightLineColor(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = LIGHT_LINE_ALPHA)

private const val LIGHT_LINE_ALPHA = 0.6f
