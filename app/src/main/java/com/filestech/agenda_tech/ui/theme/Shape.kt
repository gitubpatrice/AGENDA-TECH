package com.filestech.agenda_tech.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

internal val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * The corners of the logo wherever the app draws it — top bar, lock screen, About. The artwork's own
 * corners are barely rounded (about 3 % of its side; the launcher mask rounds it on the home screen),
 * so the app clips it at 13 %: the rounding of the splash logo (`tools/make-splash-logo.py`) and of
 * the SMS Tech logo, so the mark looks the same from the splash to the last screen.
 */
internal val LogoShape = RoundedCornerShape(percent = 13)
