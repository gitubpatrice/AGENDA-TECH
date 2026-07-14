package com.filestech.agenda_tech.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * Files Tech brand theme for Agenda Tech. Follows the system light/dark setting for now; a
 * user-selectable appearance (light / dark / dark-tech / dynamic colours) arrives with the
 * settings phase — the signature stays `AgendaTechTheme(content)` so screens don't churn.
 */
@Composable
fun AgendaTechTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkPalette else LightPalette,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
