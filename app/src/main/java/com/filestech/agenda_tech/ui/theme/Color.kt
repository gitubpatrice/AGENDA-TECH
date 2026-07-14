package com.filestech.agenda_tech.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Files Tech brand blue — shared identity across the portfolio (SMS Tech, Notes Tech…).
internal val BrandBlue = Color(0xFF2460AB)
internal val BrandBlueDark = Color(0xFFA9C7FF)

/** Single source of truth for the "destructive intent" red (delete event / calendar). */
internal val BrandDanger = Color(0xFFC62828)

internal val LightPalette = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E3FF),
    onPrimaryContainer = Color(0xFF001A40),
    secondary = Color(0xFF555F71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E3F8),
    onSecondaryContainer = Color(0xFF121C2B),
    tertiary = Color(0xFF705574),
    onTertiary = Color.White,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFDFCFF),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFDFCFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44464F),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    scrim = Color.Black,
)

// GitHub-style dark theme: slate canvas, muted grey text, bright blue accent.
internal val DarkPalette = darkColorScheme(
    primary = Color(0xFF58A6FF), // GitHub accent blue
    onPrimary = Color(0xFF0D1117),
    primaryContainer = Color(0xFF1F6FEB),
    onPrimaryContainer = Color(0xFFD9E7FF),
    secondary = Color(0xFF8B949E),
    onSecondary = Color(0xFF0D1117),
    secondaryContainer = Color(0xFF21262D),
    onSecondaryContainer = Color(0xFFC9D1D9),
    tertiary = Color(0xFF56D364), // GitHub green
    onTertiary = Color(0xFF0D1117),
    error = Color(0xFFF85149), // GitHub red
    onError = Color(0xFF0D1117),
    errorContainer = Color(0xFF8E1519),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0D1117), // canvas
    onBackground = Color(0xFFC9D1D9),
    surface = Color(0xFF161B22), // surface
    onSurface = Color(0xFFC9D1D9),
    surfaceVariant = Color(0xFF21262D),
    onSurfaceVariant = Color(0xFF8B949E),
    outline = Color(0xFF30363D),
    outlineVariant = Color(0xFF21262D),
    scrim = Color.Black,
)
